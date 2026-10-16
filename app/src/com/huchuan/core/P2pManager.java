package com.huchuan.core;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WiFi Direct (P2P) 连接管理。
 *
 * 对应 CatShare 的 P2pUtils.kt + 各 Service 里的协程逻辑。
 * Kotlin 用 CompletableDeferred 等待回调，Java 这里用 CountDownLatch 桥接。
 *
 * 典型流程：
 *   发送方 (建组):  createGroup(ssid, psk) → 等 CONNECTION_CHANGED → 拿 group
 *   接收方 (加入):  connect(ssid, psk)     → 等 CONNECTION_CHANGED → 拿 WifiP2pInfo
 */
public final class P2pManager {

    private static final String TAG = "HuChuan.P2p";

    /** 建组/连接结果。 */
    public static final class Result {
        public final boolean ok;
        public final int reason;      // WifiP2pManager.ERROR / BUSY / P2P_UNSUPPORTED
        public final String message;

        Result(boolean ok, int reason, String message) {
            this.ok = ok; this.reason = reason; this.message = message;
        }

        public String reasonText() {
            switch (reason) {
                case WifiP2pManager.ERROR:          return "ERROR";
                case WifiP2pManager.P2P_UNSUPPORTED:return "P2P_UNSUPPORTED";
                case WifiP2pManager.BUSY:           return "BUSY";
                default:                            return "code " + reason;
            }
        }

        @Override public String toString() { return ok ? "ok" : ("失败: " + reasonText()); }
    }

    /** 一次 P2P 会话的连接信息。 */
    public static final class Session {
        public final boolean isGroupOwner;
        public final String groupOwnerAddress;  // 建组方 IP（通常 192.168.49.1）
        public final String ssid;
        public final String psk;
        public final String interfaceName;      // 如 p2p-wlan0-0
        /**
         * 本机 P2P 接口的真实 MAC（p2p0）。
         *
         * 必须是真的：小米互传会拿 P2pInfo.mac 去连对端，
         * 填假地址（如 02:00:00:00:00:00）对方连不上就断开 ——
         * 表现为"文件发出去了但对方没过来"，卡在版本协商超时（P058）。
         */
        public final String interfaceMac;

        Session(boolean isGroupOwner, String goAddr, String ssid, String psk, String iface, String mac) {
            this.isGroupOwner = isGroupOwner;
            this.groupOwnerAddress = goAddr;
            this.ssid = ssid;
            this.psk = psk;
            this.interfaceName = iface;
            this.interfaceMac = mac;
        }

        @Override public String toString() {
            return "GO=" + isGroupOwner + " addr=" + groupOwnerAddress
                    + " ssid=" + ssid + " iface=" + interfaceName + " mac=" + interfaceMac;
        }
    }

    // ---- ActionListener → 阻塞桥接 ----

    private static Result awaitAction(final Action a) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger reason = new AtomicInteger(0);
        final AtomicReference<String> msg = new AtomicReference<>("");

        a.run(new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { msg.set("ok"); latch.countDown(); }
            @Override public void onFailure(int r) { reason.set(r); latch.countDown(); }
        });

        // P2P 操作通常 1-3 秒内回调；给足余量
        if (!latch.await(15, TimeUnit.SECONDS)) {
            return new Result(false, -1, "超时（15s 无回调）");
        }
        return new Result(reason.get() == 0, reason.get(), msg.get());
    }

    private interface Action { void run(WifiP2pManager.ActionListener l); }

    // ---- 实例 ----

    private final Context ctx;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;

    private BroadcastReceiver receiver;
    private volatile Session lastSession;
    private volatile boolean registered;

    // 用于等待 CONNECTION_CHANGED
    private volatile CountDownLatch connLatch;
    private volatile WifiP2pInfo lastConnInfo;
    private volatile WifiP2pGroup lastGroup;

    @SuppressLint("MissingPermission")
    public P2pManager(Context context) {
        this.ctx = context.getApplicationContext();
        this.manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) throw new IllegalStateException("设备不支持 WiFi Direct");
        this.channel = manager.initialize(ctx, Looper.getMainLooper(), new WifiP2pManager.ChannelListener() {
            @Override public void onChannelDisconnected() {
                Log.w(TAG, "P2P channel 断开");
            }
        });
    }

    /** 注册广播接收器。必须在建组/连接前调用。 */
    public void register() {
        if (registered) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                handleBroadcast(intent);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ctx.registerReceiver(receiver, f);
        registered = true;
        Log.i(TAG, "广播接收器已注册");
    }

    public void unregister() {
        if (!registered) return;
        try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
        registered = false;
    }

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            boolean enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
            Log.i(TAG, "P2P 状态: " + (enabled ? "已启用" : "已禁用") + " (" + state + ")");

        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            WifiP2pInfo info = getInfo(intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO);
            WifiP2pGroup group = getGroup(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
            Log.i(TAG, "连接变化: info=" + info + " group=" + group);

            lastConnInfo = info;
            if (group != null) {
                lastGroup = group;
                lastSession = buildSession(info, group);
            }
            CountDownLatch l = connLatch;
            if (l != null) l.countDown();
        }
    }

    @SuppressWarnings("deprecation")
    private static WifiP2pInfo getInfo(Intent i, String key) {
        try {
            if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(key, WifiP2pInfo.class);
            return i.getParcelableExtra(key);
        } catch (Throwable t) {
            try { return i.getParcelableExtra(key); } catch (Throwable t2) { return null; }
        }
    }

    /** WifiP2pGroup 没有带类型的 getParcelableExtra 重载，单独处理。 */
    @SuppressWarnings("deprecation")
    private static WifiP2pGroup getGroup(Intent i, String key) {
        try {
            return i.getParcelableExtra(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private Session buildSession(WifiP2pInfo info, WifiP2pGroup group) {
        String goAddr = null;
        boolean isGo = false;
        if (info != null) {
            isGo = info.isGroupOwner;
            if (info.groupOwnerAddress != null) goAddr = info.groupOwnerAddress.getHostAddress();
        }
        String ssid = null, psk = null, iface = null;
        try {
            ssid = group.getNetworkName();
            psk = group.getPassphrase();
            iface = group.getInterface();
        } catch (Throwable ignored) {}
        // 真实 p2p0 MAC —— 小米互传要用它来连我们（P058）
        String mac = readInterfaceMac("p2p0");
        return new Session(isGo, goAddr, ssid, psk, iface, mac);
    }

    /**
     * 读网络接口的硬件地址。
     *
     * 优先 {@code NetworkInterface.getHardwareAddress()}（不需要 root）；
     * 读不到时回落到 {@code /sys/class/net/<name>/address}。
     * 两者都失败返回 null —— 调用方必须能接受 null（宁可留空也不能编假地址）。
     */
    public static String readInterfaceMac(String name) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface ni = all.nextElement();
                if (ni == null || !name.equals(ni.getName())) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw != null && hw.length == 6) return formatMac(hw);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getHardwareAddress(" + name + ") 失败: " + t);
        }
        // 回落：直接读 sysfs
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/class/net/" + name + "/address"));
            try {
                String s = r.readLine();
                if (s != null && s.trim().length() >= 11) return s.trim().toUpperCase();
            } finally { r.close(); }
        } catch (Throwable ignored) { }
        Log.w(TAG, "拿不到 " + name + " 的 MAC");
        return null;
    }

    private static String formatMac(byte[] hw) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < hw.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", hw[i]));
        }
        return sb.toString();
    }

    // ---- 对外 API ----

    /** 是否有正在进行的组。 */
    public WifiP2pGroup requestGroupInfo() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<WifiP2pGroup> ref = new AtomicReference<>();
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                ref.set(group); latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    /** 移除现有组（若有）。 */
    public Result removeGroup() throws InterruptedException {
        return awaitAction(new Action() {
            @Override public void run(WifiP2pManager.ActionListener l) { manager.removeGroup(channel, l); }
        });
    }

    /**
     * 建组（发送方）。ssid 建议 "DIRECT-xxxxxxxx"，psk 至少 8 字符。
     * 返回建组后从广播拿到的 Session。
     */
    public Result createGroup(String ssid, String psk, boolean prefer5Ghz) throws InterruptedException {
        WifiP2pConfig.Builder b = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .enablePersistentMode(false);
        if (Build.VERSION.SDK_INT >= 29) {
            b.setGroupOperatingBand(prefer5Ghz
                    ? WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                    : WifiP2pConfig.GROUP_OWNER_BAND_2GHZ);
        }
        final WifiP2pConfig cfg = b.build();

        connLatch = new CountDownLatch(1);
        Result r = awaitAction(new Action() {
            @Override public void run(WifiP2pManager.ActionListener l) {
                manager.createGroup(channel, cfg, l);
            }
        });
        if (!r.ok) return r;

        // 注意：createGroup 成功回调后约 300ms 才收到真正的 CONNECTION_CHANGED，
        // 且中间会先来一次 groupFormed=false 的空广播 —— 必须等到 group!=null 才算就绪。
        awaitGroupReady(8);
        Log.i(TAG, "建组完成: " + lastSession);
        return r;
    }

    /** 等待 lastSession 就绪（group 非空），最多 timeoutSec 秒。 */
    private void awaitGroupReady(int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (lastSession != null) return;
            Thread.sleep(100);
        }
        Log.w(TAG, "建组超时：未在 " + timeoutSec + "s 内拿到组信息");
    }

    /**
     * 等 p2p0 真正拿到 IPv4 地址。
     *
     * ★这个方法是接收方向的关键（P069）★
     *
     * 加入组成功（收到 CONNECTION_CHANGED、groupFormed=true）**不等于**网络就绪：
     * 作为 client 时，DHCP 还要几百毫秒到几秒才把地址配到 p2p0 上。
     * 之前这里不等，直接去连 GO 的 192.168.49.1，Socket 就卡在
     * `connect()` 上直到超时，日志表现为「TLS 客户端已就绪」之后再无输出。
     *
     * 发送方向之所以一直正常，是因为我们自己是 GO（isGroupOwner=true），
     * 192.168.49.1 就是我们自己的地址，不需要等 DHCP。
     *
     * @return 拿到的 IPv4 地址；超时返回 null
     */
    public String awaitInterfaceIpv4(int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String ip = findP2pIpv4();
            if (ip != null) {
                Log.i(TAG, "p2p0 已就绪，本机地址 " + ip);
                return ip;
            }
            Thread.sleep(200);
        }
        Log.w(TAG, "等 p2p0 拿到地址超时（" + timeoutSec + "s）");
        return null;
    }

    /** 遍历 p2p* 接口找 IPv4 地址（排除回环）。 */
    private static String findP2pIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (ni == null || ni.getName() == null) continue;
                if (!ni.getName().startsWith("p2p")) continue;
                if (!ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "枚举 p2p 接口失败: " + t);
        }
        return null;
    }

    /**
     * 加入组（接收方）。
     *
     * ★严格照抄 CatShare 的顺序★（P2pReceiverService$runReceive$2）：
     *   1. 若已有 P2P 组 → removeGroup()      "A P2P group already exists, trying to remove"
     *   2. cancelConnect()                    清掉可能挂着的连接请求
     *   3. connect(config)                    只有 setNetworkName + setPassphrase
     *   4. 等 CONNECTION_CHANGED（10 秒）      "Waiting for P2P connect"
     *   5. 等 p2p0 拿到 IPv4
     *
     * 之前缺了 1、2 —— 残留的旧组会让 p2p0 停在 DOWN，
     * 于是 wss 连接一直阻塞（P069）。
     */
    public Result connect(String ssid, String psk) throws InterruptedException {
        // 1. 先清掉残留的组（CatShare: "A P2P group already exists, trying to remove"）
        WifiP2pGroup existing = null;
        try {
            java.lang.reflect.Method m = manager.getClass()
                    .getMethod("requestGroupInfo", WifiP2pManager.Channel.class,
                            WifiP2pManager.GroupInfoListener.class);
            final CountDownLatch gl = new CountDownLatch(1);
            final WifiP2pGroup[] got = new WifiP2pGroup[1];
            m.invoke(manager, channel, new WifiP2pManager.GroupInfoListener() {
                @Override public void onGroupInfoAvailable(WifiP2pGroup g) {
                    got[0] = g; gl.countDown();
                }
            });
            gl.await(2, TimeUnit.SECONDS);
            existing = got[0];
        } catch (Throwable t) {
            Log.w(TAG, "查询现有组失败（忽略）: " + t);
        }
        if (existing != null) {
            Log.i(TAG, "A P2P group already exists, trying to remove");
            removeGroup();
            Thread.sleep(500);
        }

        // 2. 清掉可能挂起的连接请求
        try {
            awaitAction(new Action() {
                @Override public void run(WifiP2pManager.ActionListener l) {
                    manager.cancelConnect(channel, l);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "cancelConnect 失败（忽略）: " + t);
        }

        final WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .build();

        connLatch = new CountDownLatch(1);
        Result r = awaitAction(new Action() {
            @Override public void run(WifiP2pManager.ActionListener l) {
                manager.connect(channel, cfg, l);
            }
        });
        if (!r.ok) return r;

        if (!connLatch.await(15, TimeUnit.SECONDS)) {
            return new Result(false, -1, "连接超时（15s 未收到 CONNECTION_CHANGED）");
        }
        // 与建组同理：首个 CONNECTION_CHANGED 可能是 groupFormed=false 的中间态，
        // 必须等到 lastSession 真正就绪（踩坑 P016）。
        awaitGroupReady(10);
        // ★还必须等 p2p0 真的拿到 IP★，否则后面 connect() 会一直阻塞（P069）
        String ip = awaitInterfaceIpv4(10);
        if (ip == null) {
            Log.w(TAG, "警告：p2p0 未拿到地址，后续连接可能失败");
        }
        Log.i(TAG, "加入完成: " + lastSession);
        return r;
    }

    public Session getSession() { return lastSession; }
    public WifiP2pInfo getConnInfo() { return lastConnInfo; }
    public WifiP2pManager getManager() { return manager; }
    public WifiP2pManager.Channel getChannel() { return channel; }

    /** 生成 "DIRECT-xxxxxxxx" 形式的 SSID（与 CatShare 一致）。 */
    public static String randomSsid() {
        return "DIRECT-" + randomChars(8);
    }

    public static String randomPsk() {
        return randomChars(8);
    }

    private static String randomChars(int n) {
        final String cs = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(n);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) sb.append(cs.charAt(r.nextInt(cs.length())));
        return sb.toString();
    }
}
