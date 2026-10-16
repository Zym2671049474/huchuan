package com.huchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.huchuan.core.BleSecurity;
import com.huchuan.core.BleUtils;
import com.huchuan.core.GattClient;
import com.huchuan.core.P2pManager;
import com.huchuan.model.DeviceInfo;
import com.huchuan.model.P2pInfo;
import com.huchuan.net.Transfer;

import org.json.JSONObject;

import java.io.File;

/**
 * 发送方前台服务。
 *
 * 完整流程：
 *   1. 起 HTTP 文件服务（拿端口）
 *   2. 建 WiFi Direct 组（拿 SSID/PSK）
 *   3. 起 WS 服务端（发送方是 server）
 *   4. BLE 扫描 → 连接接收方 → 读 DeviceInfo 拿公钥
 *   5. 加密 P2pInfo(ssid/psk/port) 写入 CHAR_P2P
 *   6. 等接收方连上 WS → 版本协商 → sendRequest → 传输
 */
public class SenderService extends Service {

    public static final String TAG = "HuChuan.Sender";
    private static final String CH = com.huchuan.core.Noti.CH_SEND;
    private static final int NOTI_ID = 1001;
    /** 结果通知用独立 id/通道，避免被 stopSelf() 连带撤掉（P092）。 */
    private static final int NOTI_DONE_ID = 1004;
    private static final String CH_DONE = com.huchuan.core.Noti.CH_DONE;

    public static final String EXTRA_PATH = "path";
    /** 由分享弹窗指定要发往哪台设备（BLE MAC）。缺省时退回"扫到谁发谁"。 */
    public static final String EXTRA_TARGET = "target";
    /** 目标设备的显示名（由 ShareActivity 传入，避免再猜一次而显示成地址）。 */
    public static final String EXTRA_TARGET_NAME = "target_name";
    public static final String ACTION_SEND_NOW = "com.huchuan.action.SEND_NOW";

    private Transfer.Sender transfer;
    private P2pManager p2p;
    private GattClient client;
    private Thread worker;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, notification("准备发送…"));
        final String path = intent != null ? intent.getStringExtra(EXTRA_PATH) : null;
        final String target = intent != null ? intent.getStringExtra(EXTRA_TARGET) : null;
        final String targetName = intent != null ? intent.getStringExtra(EXTRA_TARGET_NAME) : null;
        if (path == null) { stopSelf(); return START_NOT_STICKY; }

        // ★同一时刻只允许一个发送任务★（P089）
        //
        // 原来每次都 new 一个 Thread 并覆盖 worker 字段，旧线程没人管、
        // 继续跑，于是多次点击会并发：
        //   · 两个线程抢同一个 GATT → status=133
        //   · 更致命：旧线程超时后走 finally → cleanup()，
        //     把新线程**正在用**的 transfer/client/p2p 全关了
        //     （实测第 2 次线程把第 3 次刚建好的组拆了，
        //       导致对端等不到确认 → "对方未接受"）
        //
        // 这里串行化：旧任务先标记取消，再启动新任务。
        //
        // ★不能用"共享 boolean + join(3s) 后重置"★（P105）
        //
        // 原来的写法：
        //     cancelled = true;
        //     oldWorker.join(3000);    // 只等 3 秒
        //     cancelled = false;       // ★不管旧线程死没死都清标志★
        // 但旧线程可能**正卡在 60 秒的等待里**，3 秒根本等不到它退出。
        // 标志被清掉后它"复活"了，60 秒后跑完 finally 发出**失败通知**，
        // 把新任务已经发出的**成功通知覆盖掉**。
        //
        // 实测（两条日志完整记录）：
        //   15:28:46.913 线程4221 开始发送（建组 DIRECT-dpkePfSo）
        //   15:28:48.048 线程4221 等待对方接入…
        //   15:28:55.773 线程4265 开始第二次发送
        //   15:28:56.154 线程4265 移除旧组…   ← 4221 的组被拆
        //   15:28:59.835 线程4265 对端已连接 → 传输成功
        //   15:29:48.059 线程4221 版本协商超时 ← ★失败通知盖掉成功★
        //
        // 改成**代次（generation）**：每次新任务把代次 +1，
        // 旧任务发现自己持有的代次 != 当前代次 就立刻放弃，
        // 而且**永远不会被"重置"复活**。
        final long myGen = ++generation;
        final Thread oldWorker = worker;
        if (oldWorker != null) {
            Log.i(TAG, "已有发送任务在跑（代次将被取代），等它让位…");
            try { oldWorker.join(2000); }        // 等一小会儿，让旧任务有机会自己收尾
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (oldWorker.isAlive()) {
                // 它没退（可能卡在网络等待）—— 不要等它，靠代次让它自己作废
                Log.i(TAG, "旧任务仍在运行，已标记作废（代次 " + generation + "）");
            }
        }

        worker = new Thread(new Runnable() { @Override public void run() {
            try {
                send(new File(path), target, targetName, myGen);
            } catch (InterruptedException ie) {
                // 与 ReceiverService 同理（P117）：线程被打断是**收尾信号**，
                // 不是发送失败。误报"❌ java.lang.InterruptedException"
                // 会让用户以为传输出错。
                Log.i(TAG, "发送线程正常收尾（被 interrupt），代次 " + myGen);
            } catch (Throwable t) {
                Log.e(TAG, "发送失败", t);
                state("❌ " + t, myGen);
            } finally {
                // 只清理"自己这一轮"的资源；新任务已经起来了就别动
                cleanupIfCurrent(Thread.currentThread());
                stopSelf();
            }
        }});
        worker.start();
        return START_NOT_STICKY;
    }

    private void send(File file, String targetAddr, String targetName, final long gen) throws Exception {
        if (!file.exists()) { state("❌ 文件不存在: " + file); return; }
        state("文件: " + file.getName() + " (" + file.length() + " 字节)");
        sendingTotal = file.length();
        sentFileName = file.getName();

        // 1. HTTPS + WSS（必须 TLS，对方请求的是 https://，见 P023）
        javax.net.ssl.SSLContext tls = null;
        try {
            tls = com.huchuan.net.Tls.serverContext(this);
            state("TLS 已就绪（自签名证书）");
        } catch (Throwable t) {
            state("❌ TLS 初始化失败: " + t);
            return;
        }
        transfer = new Transfer.Sender(file, deviceName(), new Transfer.Sender.Listener() {
            // ★所有回调都先查代次★（P105）
            //
            // 被取代的旧任务还在跑（它可能卡在 60 秒等待里，
            // join(2000) 等不到它），它的回调**绝不能**再碰通知 ——
            // 实测就是它 60 秒后的"版本协商超时"把新任务的成功通知盖掉了。
            @Override public void onState(String m) {
                if (!isCurrent(gen)) return;
                // 进度类消息单独走节流通道，别刷爆限流（P090）
                if (m != null && m.startsWith("发送中 ")) {
                    int pct = parsePct(m);
                    if (pct >= 0) updateProgressThrottled(pct);
                    return;
                }
                state(m);
            }
            @Override public void onResult(boolean ok, String m) {
                if (!isCurrent(gen)) {
                    Log.i(TAG, "（已作废的一轮）丢弃结果: " + (ok ? "成功" : "失败") + " / " + m);
                    return;
                }
                // ★成功时用和接收端一致的样式★（P101）
                //   第一行"传输完成"，第二行"1个文件"（发出去的就是 1 个）
                // 失败保留具体原因，否则用户不知道发生了什么。
                if (ok) {
                    finishNotification("传输完成", sentSubtitle());
                } else {
                    finishNotification("发送失败", m);
                }
            }
        });
        // ★一个端口同时提供 /websocket 和 /download★
        // CatShare 用 Ktor 的 embeddedServer 就是单端口双路由，P2pInfo 里也只带一个 port。
        // 我们之前起两个端口、只把 wsPort 告诉对方 → 对方拿它做 GET /download 必然连不上。
        int serverPort = transfer.startUnifiedServer(tls);
        state("统一端口 " + serverPort);

        // 2. WS 已在统一服务器里提供（同端口），无需再起
        int wsPort = serverPort;

        // 3. BLE 扫描找接收方（★先扫描，后建组★）
        client = new GattClient(this, new GattClient.Listener() {
            // ★扫描回调可能每秒来十几次，绝不能每次都 notify★（P090）
            // 否则系统限流（5次/秒）会把**最终结果**那条也 shed 掉 ——
            // 表现就是"传输明明成功，却没有任何提示"。
            @Override public void onFound(android.bluetooth.BluetoothDevice d, int rssi, String name) {
                // ★把解析出的名字一起打出来★（P099）
                //
                // 排查"找不到设备"时最关键的一条信息：
                // 扫到的地址和名字分别是什么。
                // 之前只打地址，看不出名字有没有解析成功 ——
                // 而名字正是**跨随机地址匹配**的唯一依据。
                // 名字直接用回调参数（P100）：反查 client.nameOf 在
                // 构造函数返回前会拿到 null
                String nm = name != null ? name : client.nameOf(d.getAddress());
                Log.i(TAG, "发现设备 " + d.getAddress() + " 名字=" + nm
                        + " 期望名字=" + targetName);
            }
            @Override public void onDeviceInfo(android.bluetooth.BluetoothDevice d, DeviceInfo info) { state("读到设备公钥"); }
            @Override public void onState(String m) { state(m); }
        });
        // 显示优先用名字，拿不到才回落地址（P086）
        final String shown = (targetName != null && !targetName.isEmpty())
                ? targetName : targetAddr;
        // 完成通知的第二行要用它（P108：照小米互传"已成功向%2$s发送…"）
        sentTargetName = shown;
        // ★把这个 client 的"期望地址 → 名字"登记进去★（P088）
        //
        // ShareActivity 里也调过 rememberName，但那是**另一个 GattClient 实例**
        // （SenderService 在别的进程/生命周期里 new 了一个全新的），
        // 登记根本没传过来 → 按名字兜底形同虚设，第一次必然超时。
        if (targetAddr != null && targetName != null && !targetName.isEmpty()) {
            client.rememberName(targetAddr, targetName);
        }
        if (targetAddr != null) {
            state("正在寻找 " + shown + " …");
        } else {
            state("扫描接收方…");
        }
        client.startScan();

        // 等扫描到设备（最多 20 秒）
        // 有 targetAddr 时必须等到那一台，不能"扫到谁发谁"
        android.bluetooth.BluetoothDevice target = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            if (!isCurrent(gen)) { Log.i(TAG, "已被新的发送任务取代（代次 " + gen + "）"); return; }
            // ★匹配顺序：名字优先，地址兜底★（P099）
            //
            // 原来只按地址找。但对方（小米15）的 BLE 地址每轮扫描都变，
            // 分享卡片上带的地址是**上一轮**的，永远匹配不上 ——
            // 实测：
            //   14:31:57 正在寻找 51:19:81:B4:A8:5B   ← 过期地址
            //   14:31:58 发现设备 6D:A0:3C:EC:76:72   ← 真机（在广播！）
            //   14:32:18 ❌ 没找到（白等 20 秒）
            // 名字是从广播里解析的，跨轮稳定，用它先找。
            android.bluetooth.BluetoothDevice d = null;
            if (targetName != null && !targetName.isEmpty()) {
                d = client.getFoundByName(targetName);
            }
            if (d == null && targetAddr != null) {
                d = client.getFound(targetAddr);
            }
            if (d == null && targetAddr == null && targetName == null) {
                d = client.getLastFound();   // 没指定目标：扫到谁发谁
            }
            if (d != null) { target = d; break; }
            Thread.sleep(300);
        }
        if (target == null) {
            if (targetAddr == null) state("❌ 未扫描到接收方（请确认对方已开启「接收」）");
            else state("❌ 没找到 " + shown + "（可能已离开或未开启接收）");
            return;
        }
        state("已连接 " + shown);
        client.stopScan();

        DeviceInfo info = client.connectAndRead(target, 15);
        state("对端公钥长度 " + (info.key == null ? 0 : info.key.length()));

        // 4. ★锁定设备之后再建组★（P087）
        //
        // 原来建组在步骤 2、扫描在步骤 4 —— 顺序反了：
        //   · createGroup 是秒级的（~35s 里最慢的一段是 BLE 扫描）
        //   · 而 P2P 组空转几秒就会被系统回收
        // 实测：
        //   12:20:36  开始建组
        //   12:21:12  组就绪 192.168.49.128
        //   12:21:19  组被回收（只活了 7 秒）
        //   12:21:38  才扫到 Mi 15     ← 晚了 18 秒，组早没了
        // 表现就是"第一次发送失败"。
        // 改成：先扫描锁定目标（慢、且不依赖组），再建组（快），
        // 这样组一建好马上就能交付 P2pInfo。

        //    （建组本体）
        p2p = new P2pManager(this);
        p2p.register();
        android.net.wifi.p2p.WifiP2pGroup g = p2p.requestGroupInfo();
        if (g != null) { state("移除旧组…"); p2p.removeGroup(); }
        String ssid = P2pManager.randomSsid();
        String psk = P2pManager.randomPsk();
        state("建组 " + ssid + " …");
        P2pManager.Result r = p2p.createGroup(ssid, psk, true);
        if (!r.ok) { state("❌ 建组失败: " + r); return; }
        P2pManager.Session s = p2p.getSession();
        state("组已建立: " + s);


        // 5. 加密 P2pInfo 并写入
        //
        // mac 字段用 CatShare 的 RootMacReader 读真实 p2p0 地址（走 root）。
        // CatShare 无 root 时才回落 "02:00:00:00:00:00"，本机有 root，
        // 所以能拿到真值 —— 必须和它一致。
        String p2pMac = com.huchuan.core.RootMacReader.getP2pMacAddress();
        if (p2pMac == null) {
            p2pMac = "02:00:00:00:00:00";
            state("⚠️ 读不到 p2p0 MAC（" + com.huchuan.core.RootMacReader.getLastError() + "）");
        } else {
            state("p2p0 MAC = " + p2pMac);
        }
        JSONObject o = new JSONObject();
        if (info.key != null) {
            BleSecurity.SessionCipher cipher = BleSecurity.deriveSessionKey(info.key);
            o.put("id", BleUtils.getSenderId());
            o.put("ssid", cipher.encrypt(ssid));
            o.put("psk", cipher.encrypt(psk));
            o.put("mac", cipher.encrypt(p2pMac));
            o.put("port", wsPort);
            o.put("key", BleSecurity.getEncodedPublicKey());
            o.put("catShare", 7);
        } else {
            o.put("id", BleUtils.getSenderId());
            o.put("ssid", ssid);
            o.put("psk", psk);
            o.put("mac", p2pMac);
            o.put("port", wsPort);
            o.put("catShare", 7);
        }
        // 抓包对照用：把真正写进 BLE 的 JSON 原文打出来。
        // 有了它就能和 catShare 的报文逐字段 diff，不用再靠猜。
        Log.i(TAG, "P2pInfo 原文 = " + o);
        state("P2pInfo: " + o);
        client.writeP2pInfo(o.toString(), 20);
        state("已把连接信息发给对方，等待其接入…");

        // 6. 等对端连上 WS 并完成协商
        // 被新任务取代就别再干等了（60 秒会拖住后面那次）—— P089
        if (!isCurrent(gen)) { Log.i(TAG, "已被新的发送任务取代（代次 " + gen + "）"); return; }
        boolean ok = transfer.requestSend(1, file.length(), guessMime(file), 60);
        // ★只有仍是当前任务才发结果★（P105）
        // 否则这一轮的超时失败会盖掉新任务的成功通知。
        if (!isCurrent(gen)) {
            Log.i(TAG, "这一轮已被取代，丢弃结果: " + (ok ? "成功" : "失败"));
            return;
        }
        state(ok ? "传输结束" : "对方未接受");
    }

    /** 从 "发送中 42%" 里解析出 42。 */
    private static int parsePct(String m) {
        try {
            int i = m.indexOf(' ');
            int j = m.indexOf('%');
            if (i < 0 || j <= i) return -1;
            return Integer.parseInt(m.substring(i + 1, j).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private int findFreePort() throws Exception {
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    private static String guessMime(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".txt")) return "text/plain";
        return "*/*";
    }

    /**
     * 对外广播的设备名 —— 取系统「设备名称」（设置 → 关于手机 → 设备名称）。
     *
     * 原来用 Build.MODEL，那是代号（本机是 "2201122C"），
     * 对方看到一串字母数字不知道是谁。改成系统里设的真名。
     */
    private String deviceName() {
        return com.huchuan.core.DeviceName.get(this);
    }

    private void state(String m) {
        Log.i(TAG, m);
        MainActivity.postLog("[发送] " + m);
        // ★通知白名单★（P091）
        //
        // 用户拍板只保留两条：
        //   · 正在寻找 <设备> …
        //   · ✅ 对方已接受   /   ❌ <失败原因>
        // 其余（TLS/端口/建组/P2pInfo/p2p0 MAC…）只进 logcat。
        if (!isNotifiable(m)) {
            Log.i(TAG, "（仅记日志，不进通知）: " + m);
            return;
        }
        updateNotification(m);
    }

    /** 哪些状态允许出现在通知里。 */
    private static boolean isNotifiable(String m) {
        if (m == null) return false;
        if (m.startsWith("✅") || m.startsWith("❌")) return true;   // 结果
        return m.startsWith("正在寻找");                             // 唯一的中间态
    }

    /** 有更新的发送任务接管时置位，让旧任务在检查点主动退出。 */
    /**
     * 发送任务代次。
     *
     * 每次 onStartCommand +1；任务通过 {@link #isCurrent(long)} 判断
     * 自己是否已被更新的任务取代。★取代是单向的、不可逆的★——
     * 这正是它比 "共享 boolean + 重置" 可靠的地方（P105）。
     */
    private volatile long generation;

    /**
     * 只在"当前线程就是 worker"时才清理资源。
     *
     * 避免被遗弃的旧线程把新线程正在用的 transfer/client/p2p 关掉（P089）。
     */
    /** 这一轮任务是否仍是当前任务（没被新任务取代）。 */
    private boolean isCurrent(long gen) {
        return gen == generation;
    }

    /**
     * 只在仍是当前任务时才发状态（带代次的版本）。
     *
     * 被取代的旧任务**绝不能**再碰通知 —— 否则它的超时失败
     * 会盖掉新任务的成功（P105 实测过）。
     */
    private void state(String m, long gen) {
        if (!isCurrent(gen)) {
            Log.i(TAG, "（已作废的一轮，状态丢弃）: " + m);
            return;
        }
        state(m);
    }

    private void cleanupIfCurrent(Thread self) {
        if (worker != self) {
            Log.i(TAG, "本轮已不是当前任务，跳过资源清理");
            return;
        }
        cleanup();
    }

    private void cleanup() {
        try { if (transfer != null) transfer.close(); } catch (Throwable ignored) {}
        try { if (client != null) client.close(); } catch (Throwable ignored) {}
        try { if (p2p != null) { p2p.removeGroup(); p2p.unregister(); } } catch (Throwable ignored) {}
    }

    // ---- 通知 ----

    private Notification notification(String text) {
        com.huchuan.core.Noti.ensureChannels(this);   // ★渠道集中创建★（P109）
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("互传 · 发送中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                // ★归组 + 不显示时间戳★（P109，照小米互传）
                // 进度通知频繁刷新，时间戳一直跳很闹心。
                .setGroup(com.huchuan.core.Noti.GROUP)
                .setShowWhen(false)
                .setOngoing(true);
        if (transferring) {
            b.setProgress(100, Math.max(0, Math.min(100, progressPct)), false);
            if (progressSub != null) b.setSubText(progressSub);
        }
        return b.build();
    }

    /** 是否正在传输 —— 只有 true 时通知才显示进度条。 */
    private volatile boolean transferring;
    private volatile int progressPct = -1;
    private volatile String progressSub = null;
    private long lastNotifyMs;

    /**
     * ★带节流的进度上报★（P090）
     *
     * 原来每 64KB 就 state("发送中 N%") → notify() 一次。
     * 系统对同一包的通知有 5 次/秒的限流：
     *   E NotificationService: Package enqueue rate is 6.01. Shedding ...com.huchuan|1001
     *   W NotificationManager: Shedding notify (update) ... rate limit (5.0) exceeded
     * 被 shed 掉的正好是**最后那条"传输结束"** —— 于是明明成功了却没有任何提示。
     *
     * 现在：进度最多 300ms 一次；且最终结果用 force=true 直接发。
     */
    /** 用百分比上报进度（发送端只知道百分比）。 */
    private void updateProgressThrottled(int pct) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyMs < 300) return;
        lastNotifyMs = now;
        transferring = true;
        progressPct = pct;
        // 这里只有百分比，没有字节数 —— 不要伪造 "42 B / 100 B"
        Long total = sendingTotal;
        progressSub = (total != null && total > 0)
                ? human(total * pct / 100) + " / " + human(total)
                : (pct + "%");
        updateNotification("已发送 " + pct + "%");
    }

    /** 本次发送的总字节数（用于显示 "2.1 MB / 5.8 MB"）。 */
    private volatile Long sendingTotal;

    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    /**
     * 传输结束：撤掉前台通知，另发一条**独立的**结果通知（P092）。
     *
     * ★为什么不能复用 NOTI_ID★
     * 前台服务的通知是**随服务一起被系统回收的**。
     * 我们的收尾顺序是 finishNotification() → stopSelf()，
     * stopSelf() 会立刻撤掉前台通知 —— 实测：
     *   13:25:58.250  已发出结束通知: ✅ 对方已接受
     *   13:25:58.464  服务停止（FGS logger）
     * 两者只差 214ms，用户**根本看不到**那条成功提示。
     *
     * 所以：结果通知必须用**自己的 id**，与服务生命周期解耦。
     */
    /** 发送完成时的第二行，如 "1个图片"。 */
    /**
     * 发送成功通知的第二行。
     *
     * ★照小米互传的句式★（P108）：
     *   已成功向%2$s发送%1$d张图片
     *   已成功向%2$s发送%1$d个文件
     * 所以这里带上**接收方设备名**和**量词**。
     *
     * 图片用「张」（小米互传：%1$d张图片），视频/文件用「个」。
     */
    private String sentSubtitle() {
        String n = sentFileName == null ? "" : sentFileName.toLowerCase(java.util.Locale.ROOT);
        String kind = "文件";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic")) {
            kind = "照片";
        } else if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv")
                || n.endsWith(".3gp") || n.endsWith(".avi")) {
            kind = "视频";
        } else if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac")
                || n.endsWith(".aac") || n.endsWith(".m4a")) {
            kind = "音频文件";
        }
        String q = "照片".equals(kind) ? "张" : "个";
        String dev = sentTargetName;
        if (dev == null || dev.isEmpty()) return "1" + q + kind;
        return "1" + q + kind + " | 已发给 " + dev;
    }

    /** 接收方名字（通知文案用）。 */
    private volatile String sentTargetName;

    /** 本次发送的文件名（用于判断类型）。 */
    private volatile String sentFileName;

    private void finishNotification(String title, String text) {
        transferring = false;
        progressPct = -1;
        progressSub = null;
        lastNotifyMs = 0;                 // 重置节流，保证这条不被丢
        try {
            com.huchuan.core.Noti.ensureChannels(this);   // ★渠道集中创建★（P109）
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_DONE) : new Notification.Builder(this);
            b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(false)          // 用户可划掉
                    .setAutoCancel(true)
                    // ★归组★（P109，照小米互传 setGroup）
                    .setGroup(com.huchuan.core.Noti.GROUP);
            nm.notify(NOTI_DONE_ID, b.build());   // ★独立 id，不随服务消失★
            Log.i(TAG, "已发出结束通知: " + title + " / " + text);
        } catch (Throwable t) {
            Log.w(TAG, "发结束通知失败: " + t);
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, notification(text));
        } catch (Throwable ignored) {}
    }
}
