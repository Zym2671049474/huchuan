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

        Session(boolean isGroupOwner, String goAddr, String ssid, String psk, String iface) {
            this.isGroupOwner = isGroupOwner;
            this.groupOwnerAddress = goAddr;
            this.ssid = ssid;
            this.psk = psk;
            this.interfaceName = iface;
        }

        @Override public String toString() {
            return "GO=" + isGroupOwner + " addr=" + groupOwnerAddress
                    + " ssid=" + ssid + " iface=" + interfaceName;
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
        return new Session(isGo, goAddr, ssid, psk, iface);
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

    /** 加入组（接收方）。 */
    public Result connect(String ssid, String psk) throws InterruptedException {
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
