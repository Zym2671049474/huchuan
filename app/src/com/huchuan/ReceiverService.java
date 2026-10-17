package com.huchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.huchuan.core.GattServer;
import com.huchuan.core.P2pManager;
import com.huchuan.model.P2pInfo;
import com.huchuan.net.Transfer;

import org.json.JSONObject;

import java.io.File;

/**
 * 接收方前台服务。
 *
 * 流程：
 *   1. 起 GATT Server + BLE 广播（等待发送方发现）
 *   2. 发送方写入 P2pInfo → 拿到 SSID/PSK/port
 *   3. 加入 WiFi Direct 组
 *   4. 连发送方的 WS → 收 sendRequest → 回 status(1=接受)
 *   5. HTTP 拉文件到 Download/互传/
 */
public class ReceiverService extends Service {

    public static final String TAG = "HuChuan.Receiver";
    /** 磁贴/设置页用它启动接收。 */
    public static final String ACTION_START = "com.huchuan.action.START_RECEIVE";
    private static final String CH = "huchuan_recv";
    /** 「是否接收」询问专用通道：要高优先级、会响。 */
    private static final String CH_ASK = "huchuan_ask";
    private static final int NOTI_ID = 1002;

    private GattServer gatt;
    private P2pManager p2p;
    private Thread worker;

    /**
     * 当前这次发送请求的文件数量和类型 —— 只用于询问通知的文案。
     *
     * 小米互传的句式需要数量和类型（"%1$s给你发来%2$d张图片"），
     * 但 waitForUserDecision 的签名里没有，所以用字段带过来（P108）。
     */
    private volatile int askFileCount = 1;
    private volatile String askMimeType;

    @Override public IBinder onBind(Intent i) { return null; }

    /** 已经在跑了就别再起一份 —— 磁贴连点、或服务被系统重启时都会走到这里。 */
    private volatile boolean started;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, notification("等待对方连接…"));
        if (started) {
            Log.i(TAG, "接收服务已在运行，忽略重复启动");
            return START_NOT_STICKY;
        }

        // ★从磁盘恢复"用户上次的意愿"★（P114）
        com.huchuan.core.ReceiverState.restore(this);

        // ★绝不自动打开开关★ —— P114 的核心修复
        //
        // 原来这里写的是：
        //     // 进了服务就说明开关是开的（磁贴/设置页负责置位）
        //     if (!ReceiverState.isRunning()) {
        //         ReceiverState.enable(this, DEFAULT_TTL_MS, false);
        //     }
        // 这个假设是**错的**。服务会在"用户没开开关"时被拉起，
        // 而 ReceiverState 原来是**纯内存 static** —— 进程一死就归零，
        // 于是服务自动把开关打开并开始广播。
        //
        // 用户报的现象正是这个："本机没开接收开关，小米15却扫描到了本机"。
        //
        // 实测证据（17:15:10，开关是关的，直接 am start-service）：
        //   HuChuan.RecvState: 接收开关打开，定时 10 分钟
        //   dumpsys bluetooth_manager: [000001ff-…, 6] 正在广播
        if (!com.huchuan.core.ReceiverState.isRunning()) {
            Log.i(TAG, "开关未开启 → 服务直接退出，绝不广播");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (com.huchuan.core.ReceiverState.isExpired()) {
            Log.i(TAG, "开关已过期 → 服务直接退出，绝不广播");
            com.huchuan.core.ReceiverState.disable(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.i(TAG, "开关已开且未过期 → 开始广播");

        // ★挂载 MS-CDP 发现层（Windows 10/11「就近共享」）★
        //
        // 位置是刻意的：**必须在上面的 isRunning() / isExpired() 检查之后**。
        // 那两个 return 是 P114 的核心修复 —— 开关没开时服务立刻退出、
        // 绝不广播。CDP 若放在它们之前，就重演了 P114：
        // 用户没开开关，电脑却在「就近共享」里看见了本机。
        //
        // 语义要求（与 ReceiverState 严格同步）：
        //   开关开着 → 这里启动；开关关闭 → onDestroy() 里 stop()。
        // UDP 5050 占用 / 权限不足等失败只记日志，**绝不影响小米互传**，
        // CdpDiscoveryManager 内部已经 catch 了所有 Throwable。
        try {
            com.huchuan.cdp.CdpDiscoveryManager.startIfNeeded(getApplicationContext());
        } catch (Throwable t) {
            // 双保险：CdpDiscoveryManager 自己不上抛，但这里是 onStartCommand，
            // 任何从这里冒出去的异常都会让 ReceiverService 崩溃 ——
            // CDP 只是附加能力，不值得拿主功能去赌。
            Log.e(TAG, "启动 CDP 发现失败（忽略，不影响接收）", t);
        }

        started = true;
        worker = new Thread(new Runnable() { @Override public void run() {
            try {
                receive();
            } catch (InterruptedException ie) {
                // ★正常收尾，不是失败★（P117）
                //
                // 关开关 / 定时到点 / 服务被停止，都会走 onDestroy()，
                // 而 onDestroy() 里有 `worker.interrupt()` —— 工作线程此时
                // 正卡在 receive() 末尾的 Thread.sleep(1000)，于是抛
                // InterruptedException。这是**设计好的收尾信号**，
                // 没有任何东西失败。
                //
                // 原来它被下面的 catch (Throwable) 接住，打成"接收失败"
                // 并弹通知"❌ java.lang.InterruptedException"，让用户以为出错了。
                // 实测（17:36:25，用户点磁贴关闭）：
                //   Tile: onClick，当前=true
                //   RecvState: 接收开关关闭
                //   GattServer: GATT server 已停止
                //   Receiver: ❌ java.lang.InterruptedException   ← ★误报★
                Log.i(TAG, "接收线程正常收尾（被 interrupt）");
            } catch (Throwable t) {
                Log.e(TAG, "接收失败", t);
                state("❌ " + t);
            }
        }});
        worker.start();
        return START_NOT_STICKY;
    }

    private void receive() throws Exception {
        // 1. GATT server + 广播
        gatt = new GattServer(this, new GattServer.Listener() {
            @Override public void onP2pInfo(P2pInfo info) {
                Log.i(TAG, "收到 P2pInfo: " + info.describe());
                state("收到连接信息，准备加入组…");
                new Thread(new Runnable() { @Override public void run() {
                    try { joinAndDownload(info); }
                    catch (Throwable t) { state("❌ " + t); }
                }}).start();
            }
            @Override public void onState(String msg) {
                // ★传输已结束就不要再被"连接关闭"覆盖★（P084）
                //
                // 正常收尾时 WS 一定会关，onClose 回调比"已保存"晚十几毫秒，
                // 于是通知栏刚显示"✅ 已保存"就被改成"连接关闭" ——
                // 用户以为失败了。它只是收尾噪音，不是错误。
                if (finished && isNoise(msg)) {
                    Log.i(TAG, "忽略收尾噪音: " + msg);
                    return;
                }
                state(msg);
            }
        });
        gatt.start();
        String sockErr = com.huchuan.net.SocketSelfTest.run();
        // socket 自检是**诊断信息**，只在出问题时才值得打扰用户。
        // 正常时写日志即可（原来带 ✅ 前缀会被白名单放行，属于漏网）。
        if (sockErr == null) {
            Log.i(TAG, "socket 自检通过（INTERNET 权限正常）");
        } else {
            state("❌ socket 不可用: " + sockErr);
        }
        state("已开始广播，等待发送方…");

        // 保持存活直到被停止；★同时由服务自己兜住超时★（P114）
        //
        // 原来超时**只**由 ReceiverTile.tick 里的 enforceExpiry 负责，
        // 而那个 tick 只在 onStartListening（快捷面板打开着）期间才跑 ——
        // 面板一收走就 removeCallbacks(tick)，倒计时从此无人执行，
        // 服务会**一直广播下去**。
        //
        // 实测广播记录：16:43:40 → 16:55:58（12 分 18 秒），
        // 远超约定的 10 分钟。
        //
        // 现在把超时判断放进服务自己的循环 —— 与面板开不开完全无关。
        //
        // ★sleep 必须自己包 try★（P117）：收尾时 onDestroy() 会
        // `worker.interrupt()`，sleep 抛 InterruptedException。
        // 若让它冒出去，整个 receive() 会被判成"接收失败"并弹 ❌ 通知 ——
        // 但用户只是正常关了个开关。所以在这里就地捕获，**结束循环**即可：
        // 能走到这一步说明本来就是收尾，不是错误。
        while (!Thread.currentThread().isInterrupted()) {
            if (com.huchuan.core.ReceiverState.enforceExpiry(this)) {
                Log.i(TAG, "定时到点 → 自动关闭接收并停止服务");
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Log.i(TAG, "收尾中断（正常）→ 退出保活循环");
                break;      // 不 rethrow：这是收尾信号，不是失败
            }
        }
        // 退出循环 = 该收工了（超时 / 线程被中断）
        Log.i(TAG, "接收服务收工，停止广播");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** 本次接收已出结果（成功或失败），后续收尾噪音不再覆盖通知。 */
    private volatile boolean finished;

    /** 收尾噪音：连接正常关闭产生的状态，不能当作失败。 */
    private static boolean isNoise(String msg) {
        if (msg == null) return false;
        return msg.contains("连接关闭") || msg.contains("已断开");
    }

    private void joinAndDownload(P2pInfo info) throws Exception {
        // 2. 加入组
        p2p = new P2pManager(this);
        p2p.register();
        state("加入组 " + info.ssid + " …");
        P2pManager.Result r = p2p.connect(info.ssid, info.psk);
        if (!r.ok) { state("❌ 加入失败: " + r); return; }
        P2pManager.Session s = p2p.getSession();
        state("已加入: " + s);

        String host = resolveHost(s);
        state("发送方地址 " + host + ":" + info.port);

        // 3. WS 连接（wss，信任一切，对齐 CatShare 的 FakeTrustManager）
        javax.net.ssl.SSLContext clientTls = null;
        try {
            clientTls = com.huchuan.net.Tls.clientContext();
            state("TLS 客户端已就绪");
        } catch (Throwable t) {
            state("⚠️ TLS 初始化失败: " + t);
        }
        Transfer.Receiver recv = new Transfer.Receiver(recvDir(), deviceName(),
                new Transfer.Receiver.Listener() {
            @Override public void onState(String m) { state(m); }
            @Override public void onResult(boolean ok, String m) { state((ok ? "✅ " : "❌ ") + m); }
        });
        recv.connect(host, info.port, 20, clientTls);
        state("已连上发送方，等待传输请求…");

        // 4. 等 sendRequest（CatShare 发送方在收到 ack 后立刻发，给它 20s 余量）
        JSONObject req = recv.awaitSendRequest(20);
        if (req == null) { state("❌ 等待传输请求超时（对方未发 sendRequest）"); recv.close(); return; }

        String taskId = req.optString("taskId", req.optString("id"));
        String fileName = req.optString("fileName", "received.bin");
        long totalSize = req.optLong("totalSize", 0);
        int fileCount = req.optInt("fileCount", 1);
        String senderName = req.optString("senderName", "对方");
        // 记到字段里，供完成通知的副标题使用（P093）
        curFileCount = fileCount;
        curMimeType = req.optString("mimeType", "");
        curSenderName = senderName;
        // ★记住文件名，供"点击通知打开文件"反推落盘路径★（P122）
        curFileName = fileName;
        // 询问通知的文案也要数量和类型（P108）
        askFileCount = fileCount;
        askMimeType = curMimeType;
        state("来自 " + senderName + " 的文件: " + fileName
                + " (" + totalSize + " 字节, " + fileCount + " 个)");
        // 传输正式开始 → 暂停空闲计时（用户要求："没传输完毕就不关闭"）
        final boolean[] transferOpen = { true };
        com.huchuan.core.ReceiverState.beginTransfer();

        // 5. ★必须等用户点接受★，不能自动回 status
        //
        // 之前这里是「立刻 respond(1)」。那是错的：
        // 用户在通知上点"接受"需要几秒，而发送方只在有限窗口内等 status；
        // 我们抢先回了 status，真正的用户手势到达时对端已经切换状态机，
        // 表现就是「点了接受却提示对方已取消」。
        // 现在挂一个通知按钮，等用户真正点了再回。
        state("📥 来自 " + senderName + "：" + fileName + "（点通知接受）");

        // ★照抄 CatShare：等用户决定的同时，先用这条连接把缩略图拉下来★
        //
        // CatShare 的 downloadJob 顺序是：
        //   等 sendRequest(5s) → "Fetching thumbnail from …" → 弹通知问用户
        //                     → status(1,"ok") → delay(1000) → GET /download
        //
        // 为什么要在"问用户"之前抓缩略图：发送方（小米15）只给约 9 秒，
        // 超时就 GO_ENDING_SESSION 拆组。CatShare 在这 9 秒里**一直在用**
        // 这条连接，而我们原来是干等 —— 等用户点完，组已经没了（P075）。
        String thumbPath = req.optString("thumbnail", null);
        if (thumbPath != null && !thumbPath.isEmpty()) {
            try {
                state("正在获取缩略图…");
                byte[] thumb = recv.fetch(host, info.port, thumbPath, clientTls);
                state("缩略图已获取 " + (thumb == null ? 0 : thumb.length) + " 字节");
            } catch (Throwable t) {
                Log.w(TAG, "获取缩略图失败（不影响接收）: " + t);
            }
        }

        final boolean accepted = waitForUserDecision(taskId, senderName, fileName);
        if (!accepted) {
            recv.respond(taskId, 99, 3, "user refuse");
            state("已拒绝");
            recv.close();
            com.huchuan.core.ReceiverState.endTransfer(this);
            transferOpen[0] = false;
            return;
        }

        // 6. ★先下载，再回 status★（P080）
        //
        // 证据链：
        //   · 缩略图 GET 在 status **之前**发 → 每次都成功（HTTP 200）
        //   · /download 在 status **之后**发 → 每次都连不上
        //   · p2p0 在 status 之后 ~60ms 就 down（GO_ENDING_SESSION）
        // 说明：发送方一收到我们的 status 就拆组，我们再连就来不及。
        // 而缩略图证明：group 活着时，同一个 host:port 完全可连。
        // 所以把下载提到 status 之前 —— 组还活着，连接能建起来。
        state("已接受，立即下载…");

        // 7. ★等 1 秒再下载★（照抄 CatShare 的 downloadJob 顺序）
        //
        // CatShare: sendStatusIgnoreException(99, taskId, 1, "ok")
        //           → delay(1000)
        //           → createProgressNotification
        //           → GET /download?taskId=…
        //
        // 我们之前是 status 之后**立刻**发下载（实测比对方的 ack 还早 43ms），
        // 发送方还没来得及把 /download 端点准备好，而且它收到 ack 后就拆组，
        // 结果组在 91ms 后消失、下载失败（P073）。
        // 这 1 秒就是留给发送方切换状态的。
        // ★不要在这里 sleep★
        //
        // 我上一版照抄 CatShare 的 delay(1000)，但那是**错的**（P077）：
        // CatShare 的那 1 秒里在忙别的（建进度通知），而我们是纯等待。
        // 实测这 1 秒正好把 P2P 组等没了：
        //   11:37:04.53 用户点接受
        //   11:37:04.57 p2p0 down      ← sleep 期间组被拆
        //   11:37:05.54 下载 → 失败
        // 而且缩略图 GET 在 11:37:02 是成功的，证明只要组还在就能连上。
        // 所以：回完 status 立刻下载。

        // ★不要重试★
        //
        // 我上一版加了 4 次重试，结果**更糟**（P079）：上一笔传输的重试
        // 还在循环时，下一笔 sendRequest 已经到达，两条流程并发交错：
        //   54326 的下载 → 失败 → 第2次 → 此时 60860 的 sendRequest 到了
        //   → 60860 也去下载 → 54326 还在重试同一个死端口
        // 而且反复连一个已经关闭的端口，会让对端更早判定结束。
        // 一笔传输就是一笔：失败就认，把资源让给下一笔。
        boolean ok = downloadOnce(recv, host, info.port, taskId, fileName, clientTls);
        finished = true;      // 出结果了，后面的收尾噪音不再覆盖
        if (!ok) state("❌ 下载失败: " + lastDownloadError);

        // 下载结束后再回 status（对方据此收尾）
        try {
            recv.respond(taskId, 99, 1, "ok");
            state("已回复接受状态");
        } catch (Throwable t) {
            Log.w(TAG, "回 status 失败: " + t);
        }

        recv.close();
        com.huchuan.core.ReceiverState.endTransfer(this);
        transferOpen[0] = false;
    }

    private volatile String lastDownloadError = "";

    /** 单次下载尝试。 */
    private boolean downloadOnce(Transfer.Receiver recv, String host, int port,
                                 String taskId, String fileName,
                                 javax.net.ssl.SSLContext clientTls) {
        final boolean[] done = { false };
        progressPct = 0; progressSub = null; lastNotifyMs = 0;
        transferring = true;
        recv.download(host, port, taskId, fileName, new Transfer.Progress() {
            @Override public void onProgress(long sent, long total) {
                // 通知栏进度条（节流到 300ms，避免刷爆 SystemUI）
                updateProgressThrottled(sent, total);
            }
            @Override public void onDone(boolean ok, String msg) {
                done[0] = ok;
                if (!ok) lastDownloadError = msg;
                // 传输结束 → 撤掉进度条，并确保最终通知一定发出去（P090）
                transferring = false;
                progressPct = -1;
                progressSub = null;
                lastNotifyMs = 0;    // 重置节流，保证这条不被丢
                // ★用户指定的完成样式★（P093）
                //   第一行：传输完成
                //   第二行：1个图片 | 来自 Mi 15
                if (ok) {
                    doneTitle = "传输完成";
                    doneSubtitle = buildSubtitle(curFileCount, curMimeType, curSenderName);
                    // ★记住落盘路径，供"点击通知打开文件"用★（P121/P122）
                    //
                    // 两条路都试，**优先用"保存目录 + 文件名"反推**（P122）：
                    //   · 目录是我们自己定的 /sdcard/Download/互传/
                    //   · 文件名来自 sendRequest 的 fileName
                    // 不依赖 Transfer 回传字符串的格式（那个格式变过）。
                    // 回退才是解析 msg。
                    String p = resolveSavedFile();
                    if (p == null) {
                        Log.i(TAG, "反推路径失败，回退解析回调: " + msg);
                        p = stripSavedPath(msg);
                    }
                    lastSavedPath = p;
                    Log.i(TAG, "已保存路径(供点击打开): " + lastSavedPath);
                    state("✅ 已保存: " + msg);
                } else {
                    doneTitle = null;
                    doneSubtitle = null;
                    state("下载未成功: " + msg);
                }
            }
        }, clientTls);
        return done[0];
    }

    /**
     * 推断组主（发送方）的 IP。
     * 依次尝试：组信息里的 GO 地址 → p2p0 接口的网关 → 常见默认值。
     * 作为 client 侧的本地 IP 通常是 192.168.49.x，网关即 GO。
     */
    private String resolveHost(P2pManager.Session s) {
        if (s != null && s.groupOwnerAddress != null
                && !"0.0.0.0".equals(s.groupOwnerAddress)) {
            return s.groupOwnerAddress;
        }
        // 从 p2p0 接口找 IPv4 网关
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (ni.isUp() && ni.getName() != null && ni.getName().startsWith("p2p")) {
                    java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                            // 同网段 .1 通常是组主
                            String ip = a.getHostAddress();
                            int dot = ip.lastIndexOf('.');
                            String guess = ip.substring(0, dot) + ".1";
                            Log.i(TAG, "p2p 接口 " + ni.getName() + " = " + ip + " → 推测 GO " + guess);
                            return guess;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "推断 GO 地址失败", t);
        }
        return "192.168.49.1";
    }

    /** 保存目录：/sdcard/Download/互传/ */
    private File recvDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "互传");
        if (!d.exists()) d.mkdirs();
        return d;
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
        MainActivity.postLog("[接收] " + m);
        // ★通知白名单★（P091）
        //
        // 用户拍板只保留两条：
        //   · 等待发送方…
        //   · ✅ 已保存: <路径>   /   ❌ 下载失败: <原因>
        // 其余（加入组、TLS 就绪、缩略图、发送方地址…）都是实现细节，
        // 只进 logcat，**不进通知**。
        //
        // 好处不只是界面干净：通知有 5 次/秒的配额（P090），
        // 白名单等于从源头掐掉了刷屏。
        if (!isNotifiable(m)) {
            Log.i(TAG, "（仅记日志，不进通知）: " + m);
            return;
        }
        updateNotification(m);
    }

    /** 哪些状态允许出现在通知里。 */
    private static boolean isNotifiable(String m) {
        if (m == null) return false;
        // ✅ 已保存 / ❌ 下载失败 / ❌ 加入失败 / ❌ 加入失败等结果类
        if (m.startsWith("✅") || m.startsWith("❌")) return true;
        return m.startsWith("已开始广播");
    }

    @Override
    public void onDestroy() {
        started = false;
        // 服务没了，开关状态也要跟着落下去
        // （否则磁贴显示"开"、实际没人广播，用户会以为在等对方）
        com.huchuan.core.ReceiverState.disable(this);
        super.onDestroy();
        try { if (gatt != null) gatt.stop(); } catch (Throwable ignored) {}
        try { if (p2p != null) { p2p.removeGroup(); p2p.unregister(); } } catch (Throwable ignored) {}
        // ★CDP 发现必须在这里停★
        //
        // onDestroy() 是服务**所有**退出路径的唯一汇合点：用户关磁贴、
        // 定时到点自动关、系统回收服务 —— 全都走到这里。少了这一行就是
        // P114 同类问题：开关已经关了，UDP 5050 却还在应答，
        // 电脑的「就近共享」里本机一直挂着、且白刷局域网流量。
        // stop() 幂等且内部吞异常，重复调 / 没启动过都安全。
        com.huchuan.cdp.CdpDiscoveryManager.stop();
        if (worker != null) worker.interrupt();
    }

    /** 是否正在传输文件 —— 只有 true 时通知才显示进度条。 */
    private volatile boolean transferring;
    /** 完成后的标题（null = 还没完成，用默认标题）。 */
    private volatile String doneTitle;
    /** 完成后的第二行，形如 "1个图片 | 来自 Mi 15"。 */
    private volatile String doneSubtitle;
    /** 本次传输的元信息（供完成通知使用；匿名内部类不能捕获非 final 局部变量）。 */
    private volatile int curFileCount = 1;
    private volatile String curMimeType = "";
    private volatile String curSenderName = "对方";
    /**
     * 本次接收的文件名（来自 sendRequest 的 fileName）（P122）。
     *
     * 用它 + {@link #recvDir()} 反推落盘路径，比解析回调文案可靠。
     */
    private volatile String curFileName = "";
    /** 当前进度（0-100）；<0 表示不确定进度（转圈）。 */
    private volatile int progressPct = -1;
    /** 进度条右侧文字，例如 "2.1 MB / 5.8 MB"。 */
    private volatile String progressSub = null;

    /**
     * 最近一次成功接收的文件绝对路径（P121）。
     *
     * 用于"点击通知打开文件"。为空表示没有可打开的文件
     * （还没传完 / 传失败）—— 这时通知点击不挂 PendingIntent。
     */
    private volatile String lastSavedPath = null;

    /**
     * 从 onDone 的回调参数里取出真实文件路径（P121）。
     *
     * ★踩过的坑★：我原来以为 {@code msg} 是
     * {@code "✅ 已保存: /path/to/file"}（因为通知里显示的是那样），
     * 于是写了个"按 : 剥前缀"的解析。但其实
     * {@code Transfer.download} 传过来的是**裸路径**：
     *
     * <pre>
     * Transfer.java:585
     *   progress.onDone(true, last == null ? "(空)" : last.getAbsolutePath());
     * </pre>
     *
     * 结果 {@code lastIndexOf(": ")} 找不到 → 返回 null →
     * {@code lastSavedPath=null} → 通知不挂 contentIntent → **点击无反应**。
     * 日志实证：
     * <pre>
     *   已保存路径: null
     *   ✅ 已保存: /storage/emulated/0/Download/互传/Screenshot_...jpg
     * </pre>
     *
     * "✅ 已保存: " 那个前缀是 {@code state()} 自己加的，只用于**显示**。
     *
     * 现在两种形态都兼容：先按裸路径试，不行再剥前缀。
     * 最后**必须校验文件真的存在** —— 不存在就不挂点击。
     */
    private static String stripSavedPath(String msg) {
        if (msg == null || msg.trim().isEmpty()) return null;
        String raw = msg.trim();

        // 形态 1：裸路径（Transfer 实际传的）
        String p = existingPath(raw);
        if (p != null) return p;

        // 形态 2："✅ 已保存: <path>"（带前缀的文案）
        int i = raw.lastIndexOf(": ");
        if (i >= 0) {
            p = existingPath(raw.substring(i + 2).trim());
            if (p != null) return p;
        }
        return null;
    }

    /** 路径存在就返回它的绝对路径，否则 null。 */
    private static String existingPath(String p) {
        if (p == null || p.isEmpty()) return null;
        try {
            java.io.File f = new java.io.File(p);
            return f.exists() ? f.getAbsolutePath() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ★按"保存目录 + 文件名"反推落盘路径★（P122）。
     *
     * 这是更可靠的做法 —— 不依赖 {@code onDone} 回传的字符串格式，
     * 而是直接用我们知道的两件事拼出来：
     *   · 保存目录：{@link #recvDir()} = {@code /sdcard/Download/互传/}
     *   · 文件名：{@code sendRequest} 里的 {@code fileName}（存在
     *     {@link #curFileName}）
     *
     * 为什么要这样：{@code Transfer} 的回调文案格式**变过**（裸路径 vs 带前缀），
     * 解析字符串是脆的；而"目录 + 名字"是我们自己定的，不会漂。
     *
     * ★重名怎么处理★（这一步很关键，第一版写错了）
     *
     * 用户要求"不覆盖"，所以第二次收同名文件会存成 {@code name(1).jpg}。
     * 于是"精确名存在就返回"是**错的** —— 那会命中**上一次的旧文件**：
     *
     * <pre>
     * 18:06 收到 → Screenshot_xxx.jpg        （旧）
     * 18:17 再收到 → Screenshot_xxx(1).jpg   （新，本次的）
     * 精确名查询返回的是 18:06 那个 ← 错
     * </pre>
     *
     * 正确做法：把 {@code name} 和 {@code name(N).ext} **全都收集起来，
     * 取 mtime 最新的那个** —— 本次刚落盘的文件必然是最新的。
     *
     * @return 存在的绝对路径；找不到返回 null
     */
    private String resolveSavedFile() {
        String name = curFileName;
        if (name == null || name.isEmpty()) return null;
        try {
            File dir = recvDir();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";

            File best = null;
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    String fn = f.getName();
                    // 命中「原名」或「原名(N)后缀」
                    boolean hit = fn.equals(name)
                            || (fn.startsWith(base + "(") && fn.endsWith(")" + ext));
                    if (!hit) continue;
                    // ★取 mtime 最新的★ —— 本次落盘的一定最新
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            }
            if (best != null) {
                Log.i(TAG, "反推落盘文件: " + best.getName()
                        + "（候选命中，取最新）");
                return best.getAbsolutePath();
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "反推落盘路径失败", t);
            return null;
        }
    }

    /**
     * 构造"点击通知打开文件"的 Intent（P121）。
     *
     * 完全照小米互传的写法（反编译 `com/miui/mishare/view/d.d()`）：
     *
     * <pre>
     * new Intent(ACTION_VIEW)
     *   .setDataAndType(contentUri, mimeType)
     *   .addFlags(FLAG_GRANT_READ_URI_PERMISSION)     // 它写的是 addFlags(1)
     * </pre>
     *
     * ★三个关键点★
     *   1. 用 **content://**，不是 file:// —— API 24+ 用 file:// 会抛
     *      FileUriExposedException
     *   2. 必须 `setDataAndType`（同时设 data 和 type），
     *      只 setData 系统不知道用什么打开
     *   3. 必须 `FLAG_GRANT_READ_URI_PERMISSION` —— 查看器是**另一个进程**，
     *      没有这个 flag 它读不到我们的 provider
     *
     * @return Intent；没有可打开的文件时返回 null
     */
    private android.content.Intent openFileIntent() {
        String path = lastSavedPath;
        if (path == null) return null;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return null;

            android.net.Uri uri = FileContentProvider.uriFor(f);

            // ★MIME 优先用 sendRequest 里带的★（P122）
            //
            // 原来这里是 getContentResolver().getType(uri) —— 走一次
            // provider 的 IPC 查询。那条路能work，但有两个毛病：
            //   · 多一次跨进程调用（在通知构建路径上，越短越好）
            //   · 万一 provider 还没就绪/被系统回收，返回 null 就退化成 "*/*"，
            //     而 "*/*" 会让系统弹出**所有**应用的选择器（体验差）
            //
            // 而 `curMimeType` 是发送方在 sendRequest 里明确给的（比如
            // image/jpeg），比我们反推准。拿不到才回退到 provider 推断。
            String mime = curMimeType;
            if (mime == null || mime.isEmpty()) {
                try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) { }
            }
            if (mime == null || mime.isEmpty()) mime = "*/*";

            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            // ★这个 flag 是必须的★ —— 查看器是**另一个进程**，
            // 没有它就读不到我们的 FileContentProvider。
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i(TAG, "打开文件的 Intent: uri=" + uri + " mime=" + mime);
            return i;
        } catch (Throwable t) {
            Log.w(TAG, "构造打开文件的 Intent 失败", t);
            return null;
        }
    }

    private Notification notification(String text) {
        // ★渠道集中创建★（P109）
        com.huchuan.core.Noti.ensureChannels(this);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        // ★通知样式（按用户要求）★（P093）
        //
        //   左侧：app 图标
        //   第一行：传输完成
        //   第二行：1个文件或图片 | 来自（对方设备名）
        //
        // 所以标题固定为"传输完成"，副标题由 buildSubtitle() 拼出。
        // smallIcon 的说明见 P094/P096：
        // 它**不是**"右侧图标"（那是 largeIcon，见下）。
        // 这里只是：传输中用下载箭头，完成态用透明占位
        // （setSmallIcon 是必填项，不能省）。
        // ★第一行 / 第二行按用户要求固定★（P093）
        //   第一行：传输完成（或"互传 · 接收中"）
        //   第二行：1张照片 | 来自 Mi 15
        //
        // 句式参考小米互传 strings.xml（P108）：
        //   · 完成：成功接收来自%1$s的%2$d张图片
        //   · 进行：正在接收来自%1$s的%2$d张图片
        //   · 询问：%1$s给你发来%2$d张图片
        // 用户明确要求第一行就是"传输完成"，所以这里保留**标题=状态**、
        // **副标题=数量+来源**的两行结构，只是把用语换成小米互传的说法。
        boolean done = doneTitle != null;
        b.setContentTitle(done ? doneTitle : "互传 · 接收中")
                .setContentText(doneSubtitle != null ? doneSubtitle : text)
                .setSmallIcon(done ? R.drawable.ic_blank
                                   : android.R.drawable.stat_sys_download)
                // ★不要设 setLargeIcon★（P096）
                //
                // 用户要求"去掉右面的图标"。实测（PixelOS 16.2）：
                //   · setLargeIcon 画在卡片**右侧**，不是左侧
                //   · 移除它 → 右侧图标消失，左侧 app 图标仍由系统绘制
                //   · 加着它 → 左右各一个圆（两个都是 launcher 图标）
                //
                // 所以"左侧 app 图标 + 去掉右侧"= **不设 largeIcon**。
                // 我一开始以为 largeIcon 是左侧那个，改错了方向。
                .setOngoing(true);
        // ★普通通知（接收中/完成）默认收起★（P097）
        //
        // 用户要求：这两个通知默认**不展开**。
        // 做法：**不设 setStyle** —— 没有 BigTextStyle 的通知
        // 在系统看来就是固定两行，不存在"展开"这回事。
        // （之前 done 分支里 setStyle(null) 是多余的，这里统一处理。）
        b.setStyle(null);
        // ★归组★（P109，照小米互传 `setGroup("mishare_persist")`）
        //
        // 把同一个 app 的所有通知放进一个组，用户折叠时是一整块，
        // 不会散成好几条占满通知栏。
        b.setGroup(com.huchuan.core.Noti.GROUP);
        if (done) {
            // ★完成态去掉所有"右侧"元素★（P095）
            //
            // dumpsys 显示完成通知里有：
            //   android.showWhen=Boolean (true)   ← 右上角**时间戳**
            //   android.template=String           ← 展开标记
            b.setShowWhen(false);        // 不画时间戳
            b.setSubText(null);
            b.setProgress(0, 0, false);  // 清掉可能残留的进度区

            // ★点击通知直接打开文件★（P121，照小米互传）
            //
            // 只有"真的收到了文件"才挂点击 —— 传输中/失败时点它没有意义，
            // 那时不设 contentIntent，系统点击只做"取消通知"。
            android.content.Intent open = openFileIntent();
            if (open != null) {
                // ★FLAG_IMMUTABLE 是 API 31+ 的强制要求★
                // targetSdk 35 下不传 flag 会抛 IllegalArgumentException。
                // 我们不需要被修改，所以用 IMMUTABLE（更安全）。
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 1002, open, flags);
                b.setContentIntent(pi);
                // 点了之后收起来 —— 文件已经打开，通知留着挡地方
                b.setAutoCancel(true);
            }
        }
        // 注意：这里**不要**再 setStyle(BigTextStyle) ——
        // 那会把上面的 setStyle(null) 撤销，通知又变成可展开的（P097）。
        // ★进度条只在实际传输时显示★（P085）
        //
        // 待机（等对方连接）时不该有进度条 —— 只有真正在收发文件才有。
        // 用 transferring 显式控制，而不是靠 pct 的值猜。
        if (transferring) {
            int pct = progressPct;
            if (pct < 0) {
                b.setProgress(0, 0, true);        // 已开始但总量未知：转圈
            } else {
                b.setProgress(100, Math.max(0, Math.min(100, pct)), false);
            }
            if (progressSub != null) b.setSubText(progressSub);
        }
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            // ★文案给打印出来，方便核对★（P109）
            // dumpsys 之外，日志里也能直接看到"这条通知长什么样"。
            Notification n = notification(text);
            android.os.Bundle ex = n.extras;
            Log.i(TAG, "通知[" + NOTI_ID + "] 标题=" + ex.getCharSequence("android.title")
                    + " 正文=" + ex.getCharSequence("android.text")
                    + " 副文本=" + ex.getCharSequence("android.subText")
                    + " 进度=" + ex.getInt("android.progress") + "/" + ex.getInt("android.progressMax")
                    + " 组=" + ex.getString("android.group"));
            nm.notify(NOTI_ID, n);
        } catch (Throwable ignored) {}
    }

    /** 更新通知里的进度条。 */
    private void updateProgress(int pct, String sub) {
        progressPct = pct;
        progressSub = sub;
        updateNotification(sub == null ? (pct + "%") : sub);
    }

    /**
     * 拼第二行："1个文件或图片 | 来自（对方设备名）"。
     *
     * 数量来自 sendRequest 的 fileCount；
     * 类型按 mimeType 判定（图片/视频/文件）；
     * 设备名来自 senderName。
     */
    private static String buildSubtitle(int fileCount, String mimeType, String senderName) {
        int n = Math.max(1, fileCount);
        String dev = (senderName == null || senderName.isEmpty()) ? "对方" : senderName;
        // ★按小米互传的量词★（P108）
        //
        // 小米互传的 strings.xml 里对"张/个"分得很细：
        //   接收%1$d张照片        接收%1$d个文件
        //   接收%1$d个视频        接收%1$d个动态照片
        // 图片用「张」，视频/文件用「个」。
        // 我们原来一律用「个」（"1个图片"），读起来不如对方自然。
        return n + "个" + kindWord(mimeType) + " | 来自 " + dev;
    }

    /**
     * 询问通知的第一行 —— 照小米互传 `%1$s给你发来%2$d张图片` 的句式。
     *
     * 小米互传对量词分得很细（张/个），这里做同样的区分。
     */
    private static String askTitle(String senderName, int fileCount, String mimeType) {
        String dev = (senderName == null || senderName.isEmpty()) ? "对方" : senderName;
        int n = Math.max(1, fileCount);
        String kind = kindWord(mimeType);
        if ("照片".equals(kind)) return dev + "给你发来 " + n + " 张照片";
        if ("视频".equals(kind)) return dev + "给你发来 " + n + " 个视频";
        return dev + "给你发来 " + n + " 个文件";
    }

    /**
     * 文件类型的中文量词/名词。
     *
     * 对照小米互传 strings.xml（P108）：
     *   image/*  → 照片（量词「张」）
     *   video/*  → 视频
     *   其它     → 文件
     */
    private static String kindWord(String mimeType) {
        if (mimeType == null) return "文件";
        if (mimeType.startsWith("image/")) return "照片";
        if (mimeType.startsWith("video/")) return "视频";
        if (mimeType.startsWith("audio/")) return "音频文件";
        return "文件";
    }

    /** 把字节数格式化成可读大小。 */
    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    /** 进度节流：通知栏刷新太频繁会卡 UI。 */
    private long lastNotifyMs;

    private void updateProgressThrottled(long sent, long total) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyMs < 300) return;
        lastNotifyMs = now;
        int pct = total > 0 ? (int) (sent * 100 / total) : -1;
        String sub = total > 0
                ? human(sent) + " / " + human(total)
                : human(sent);
        updateProgress(pct, sub);
    }

    // ============ 等用户点「接受 / 拒绝」 ============

    /** 用户的选择结果，由 ReceiverActionReceiver 写入。 */
    static final java.util.concurrent.atomic.AtomicInteger DECISION =
            new java.util.concurrent.atomic.AtomicInteger(0);   // 0=未决定 1=接受 2=拒绝

    private static final int NOTI_ASK_ID = 1003;

    /**
     * 弹一个带「接受 / 拒绝」按钮的通知，等用户点。
     *
     * ★超时 10 秒★ —— 与 CatShare 完全一致。
     * CatShare 的 P2pReceiverService$runReceive$2$1$downloadJob$1 里：
     *     const-wide/16 v8, 0x2710          # 10000 ms
     *     withTimeoutOrNull(v8, …)          # 包住"等用户点"
     *     ├─ 点了接受 → sendStatusIgnoreException(99, taskId, 1, "ok")
     *     └─ 超时/拒绝 → sendStatusIgnoreException(99, taskId, 3, "user refuse")
     * 10 秒内没点就当作"用户拒绝"，并把 type=3 发给发送方 ——
     * 发送方那边显示的就是「对方已取消」。
     *
     * @return true=接受 false=拒绝或超时
     */
    private boolean waitForUserDecision(String taskId, String senderName, String fileName) {
        DECISION.set(0);
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            // ★渠道统一由 Noti 创建★（P109，照小米互传集中管理）
            // CH_ASK 会响（HIGH + 震动），其余传输渠道静音。
            com.huchuan.core.Noti.ensureChannels(this);

            Intent acc = new Intent(this, ReceiverActionReceiver.class)
                    .setAction(ReceiverActionReceiver.ACTION_ACCEPT);
            Intent rej = new Intent(this, ReceiverActionReceiver.class)
                    .setAction(ReceiverActionReceiver.ACTION_REJECT);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_ASK) : new Notification.Builder(this);
            // ★询问通知的文案照小米互传★（P108）
            //
            // 小米互传 strings.xml：
            //   %1$s给你发来%2$d张图片        （%1$s = 对方设备名）
            //   %1$s给你发来%2$d个文件
            // 标题用这句（口语、有主语），比"互传 · 是否接收"更像对方。
            // 第二行放文件名 —— 小米互传不放，但用户之前要求能看到文件名，
            // 所以保留（原需求："第二行显示1个文件或图片|来自（对方设备名）"）。
            b.setContentTitle(askTitle(senderName, askFileCount, askMimeType))
                    .setContentText(fileName)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    // 归组（P109）
                    .setGroup(com.huchuan.core.Noti.GROUP)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setOnlyAlertOnce(false)
                    .addAction(new Notification.Action.Builder(
                            android.graphics.drawable.Icon.createWithResource(
                                    this, android.R.drawable.ic_menu_close_clear_cancel),
                            "✕ 拒绝",
                            PendingIntent.getBroadcast(this, 1, rej, fl)).build())
                    .addAction(new Notification.Action.Builder(
                            android.graphics.drawable.Icon.createWithResource(
                                    this, android.R.drawable.stat_sys_download_done),
                            "↓ 接收",
                            PendingIntent.getBroadcast(this, 2, acc, fl)).build());
            // ★询问通知默认展开★（P097）
            //
            // 用户要求：接收/拒绝这个通知**默认展开**，
            // 这样才能一眼看到「✕ 拒绝 / ↓ 接收」两个按钮。
            //
            // 做法：加 BigTextStyle —— 它把通知标记为**可展开**，
            // 并且展开态会完整显示正文（含文件名，可能很长）。
            // 配合已有的一对 addAction，系统进来就是展开的样子。
            b.setStyle(new Notification.BigTextStyle()
                    .bigText(senderName + " 想发送：" + fileName));
            nm.notify(NOTI_ASK_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "弹接受通知失败，退化为直接接受", t);
            return true;
        }

        // CatShare 用 withTimeoutOrNull(10000)。但实测发送方（小米15）
        // 在 sendRequest 之后约 **9 秒** 就 GO_ENDING_SESSION 拆组，
        // 所以给用户 10 秒是没意义的 —— 第 9 秒点下去时组已经没了（P075）。
        // 收紧到 8 秒，保证用户手势落在发送方还活着的窗口内。
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            int d = DECISION.get();
            if (d == 1) { cancelAsk(); return true; }
            if (d == 2) { cancelAsk(); return false; }
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        cancelAsk();
        Log.i(TAG, "用户 8 秒内未响应，按 CatShare 的行为当作拒绝");
        return false;
    }

    private void cancelAsk() {
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(NOTI_ASK_ID);
        } catch (Throwable ignored) {}
    }
}
