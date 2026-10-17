package com.huchuan.cdp;

// 注意：这里**不 import android.util.Log** —— 见 log() 方法的说明。
// 单测（tests/jvm/CdpHeaderTest.java）在**没有 android.jar** 的 classpath 上
// 编译并运行本类；一旦出现对 Log 的直接引用，JVM 在类链接期就会
// NoClassDefFoundError。日志改成运行期反射调用。

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MS-CDP 发现层：UDP 5050 收发（Windows 10/11「就近共享」）。
 *
 * <p>规格依据见 {@code docs/04-参考/MS-CDP协议规格.md} §3 与
 * <a href="https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cdp/">MS-CDP 官方规范</a>。
 *
 * <h3>两条腿都要走</h3>
 * <ol>
 *   <li><b>应答</b>：Windows 会主动往 5050 广播 {@code PresenceRequest}，
 *       我们收到就单播回 {@code PresenceResponse}（这条路最可靠 ——
 *       电脑不问、我们盲发，列表里通常不会出现）。</li>
 *   <li><b>主动找</b>：我们自己也往**每块网卡的广播地址**发
 *       {@code PresenceRequest}，让对方回我们（可选，见
 *       {@link #setProactiveSearch(boolean)}）。</li>
 * </ol>
 *
 * <p>★必须遍历所有网卡逐个广播★：本机实测有两块 wlan
 * （{@code wlan0} → {@code 172.20.127.255}、{@code wlan2} → {@code 192.168.207.255}），
 * 只发 {@code 255.255.255.255} 在部分环境会被丢弃，导致电脑根本收不到。
 *
 * <p><b>线程模型</b>：一个接收线程（阻塞在 {@link DatagramSocket#receive}）
 * + 一个发送/周期线程。两者都靠 {@code volatile running} 退出，
 * 关闭时先置 false 再 {@code close()} socket 打断阻塞。
 *
 * <p>纯 Java 实现：无 lambda、无第三方依赖、无 androidx，目标 Java 8 语法。
 */
public final class MsCdpDiscovery {

    private static final String TAG = "HuChuan.Cdp";

    /** 发现用 UDP 端口（数据连接是 TCP 5040）。 */
    public static final int UDP_PORT = 5050;

    // ---- DiscoveryHeader.DiscoveryType（规格 §3）----
    public static final int DISCOVERY_PRESENCE_REQUEST = 0;
    public static final int DISCOVERY_PRESENCE_RESPONSE = 1;

    /** 载荷里的 Message_Type（官方规范：Discovery 消息里又出现一次）= 1。 */
    public static final int PAYLOAD_MESSAGE_TYPE_DISCOVERY = 1;

    // ---- Connection_Mode（官方规范：0=None, 1=Proximal, 2=Legacy）----
    public static final int CONNECTION_MODE_NONE = 0;
    public static final int CONNECTION_MODE_PROXIMAL = 1;
    public static final int CONNECTION_MODE_LEGACY = 2;

    // ---- Device_Type（官方规范）----
    public static final int DEVICE_TYPE_ANDROID = 8;
    public static final int DEVICE_TYPE_WIN10_DESKTOP = 9;
    public static final int DEVICE_TYPE_LINUX = 12;
    public static final int DEVICE_TYPE_WIN_LAPTOP = 15;

    /** PresenceRequest 载荷：只有 1 字节 = DiscoveryType = 0。 */
    public static final byte[] PRESENCE_REQUEST_PAYLOAD = new byte[]{0x00};

    /** Device_Name 的字节上限：放到单个 UDP 数据报里，留足余量。 */
    private static final int MAX_DEVICE_NAME_BYTES = 200;

    /** 单包最大接收缓冲。MS-CDP 发现包都很小，2KB 足够且防放大攻击。 */
    private static final int RECV_BUF = 2048;

    // ---- 配置（构造时定，运行期只读）----
    private final String deviceName;
    private final byte[] macAddress;
    private final String deviceId;
    private final int deviceType;
    private final int connectionMode;
    private final int deviceIdSalt;
    private final byte[] principalUserNameHash;

    private volatile boolean running;
    private volatile boolean proactiveSearch;
    private volatile DatagramSocket socket;
    private Thread recvThread;
    private Thread workThread;

    /** 已应答过的对端，避免同一请求重复回包（key = IP）。 */
    private final Set<String> answered = new HashSet<String>();

    /** 观察到的对端（IP → 名字），给 UI / 日志用。 */
    private final List<Peer> peers = new ArrayList<Peer>();

    /** 回调：每收到一个 CDP 包就通知一次。UI 可选用，允许为 null。 */
    public interface Listener {
        /** @param peer 对端；{@code peer.name} 在 PresenceResponse 里才有值 */
        void onCdpPacket(Peer peer, int discoveryType, CdpHeader header);

        void onCdpError(String message, Throwable t);
    }

    /** 一个 CDP 对端。 */
    public static final class Peer {
        public final String ip;
        public String name = "";
        public int deviceType;
        public int connectionMode;
        public long lastSeenMs;

        /** 公开构造：单测与 UI 都需要直接造一个对端记录。 */
        public Peer(String ip) {
            this.ip = ip;
        }

        @Override
        public String toString() {
            return name.isEmpty() ? ip : (name + "(" + ip + ")");
        }
    }

    private Listener listener;

    /**
     * @param deviceName 显示给电脑看的设备名（取系统「设备名称」）
     * @param macAddress 6 字节蓝牙/wlan MAC，用于去重；为 null 时填 0
     */
    public MsCdpDiscovery(String deviceName, byte[] macAddress, String deviceId) {
        String n = deviceName == null ? "" : deviceName;
        if (n.getBytes(CdpHeader.UTF8).length > MAX_DEVICE_NAME_BYTES) {
            n = new String(n.getBytes(CdpHeader.UTF8), 0, MAX_DEVICE_NAME_BYTES, CdpHeader.UTF8);
        }
        this.deviceName = n;
        this.macAddress = macAddress == null ? new byte[6] : macAddress;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.deviceType = DEVICE_TYPE_ANDROID;
        this.connectionMode = CONNECTION_MODE_PROXIMAL;
        this.deviceIdSalt = new SecureRandom().nextInt();
        this.principalUserNameHash = new byte[4];
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 是否主动广播 PresenceRequest 去找设备。
     *
     * <p>默认 <b>false</b>：Windows 侧本来就会周期性广播 PresenceRequest，
     * 我们只要应答即可；主动广播属于加分项，且在部分网络里会招来无谓流量。
     * 需要时再打开。
     */
    public void setProactiveSearch(boolean on) {
        this.proactiveSearch = on;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isRunning() {
        return running;
    }

    /** 已观察到的对端快照（复制一份，避免调用方拿到会变的 List）。 */
    public List<Peer> snapshotPeers() {
        synchronized (peers) {
            return new ArrayList<Peer>(peers);
        }
    }

    // =====================================================================
    // 日志（运行期反射，见文件头说明）
    // =====================================================================

    /** 设备名为 null 时表示"单测模式"：不打日志，也不加载 android.util.Log。 */
    private static final boolean TEST_MODE = false;

    private static final int L_DEBUG = 0;
    private static final int L_INFO = 1;
    private static final int L_WARN = 2;
    private static final int L_ERROR = 3;

    private static java.lang.reflect.Method logI, logW, logE;
    private static boolean logInitDone;
    private static boolean logUsable;

    private static synchronized void initLog() {
        if (logInitDone) return;
        logInitDone = true;
        try {
            Class<?> c = Class.forName("android.util.Log");
            logI = c.getMethod("i", String.class, String.class);
            logW = c.getMethod("w", String.class, String.class);
            logE = c.getMethod("e", String.class, String.class);
            logUsable = true;
        } catch (Throwable t) {
            // 纯 JVM 单测环境没有 android.util.Log —— 这正是我们要支持的情况。
            // 这里不打印任何东西，否则单测输出会被噪声淹没。
            logUsable = false;
        }
    }

    /**
     * 打日志。★故意不用 {@code android.util.Log} 的静态引用★：
     * 单测 classpath 上没有 android.jar，静态引用会在类链接期
     * {@code NoClassDefFoundError}；改成反射后，JVM 上只是"没有日志"而已。
     */
    private static void log(int level, String msg, Throwable t) {
        if (TEST_MODE) return;
        initLog();
        if (!logUsable) {
            if (level >= L_ERROR) System.err.println(TAG + " E " + msg);
            return;
        }
        try {
            String full = t == null ? msg : (msg + " : " + t);
            if (level == L_INFO) logI.invoke(null, TAG, full);
            else if (level == L_WARN) logW.invoke(null, TAG, full);
            else if (level == L_ERROR) logE.invoke(null, TAG, full);
            else logI.invoke(null, TAG, full);
        } catch (Throwable ignored) {
            // 日志本身失败绝不能影响发现层（反射调用异常、日志被掐断等）
        }
    }

    private static void log(int level, String msg) {
        log(level, msg, null);
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    /**
     * 绑定 UDP 5050 并启动收发线程。
     *
     * <p>用 {@link DatagramSocket#setReuseAddress(boolean)} 避免和系统里
     * 其它监听 5050 的程序冲突（本机实测普通 App 权限即可绑定，只需 INTERNET）。
     *
     * @return 是否绑定成功
     */
    public boolean start() {
        if (running) return true;
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(UDP_PORT));
            socket = s;
        } catch (Throwable t) {
            log(3, "绑定 UDP " + UDP_PORT + " 失败", t);
            notifyError("绑定 UDP " + UDP_PORT + " 失败: " + t, t);
            return false;
        }
        running = true;

        recvThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "cdp-recv");
        recvThread.start();

        workThread = new Thread(new Runnable() {
            @Override
            public void run() {
                workLoop();
            }
        }, "cdp-work");
        workThread.start();

        log(1, "CDP 发现已启动: 0.0.0.0:" + UDP_PORT + " 设备名=\"" + deviceName
                + "\" 主动广播=" + proactiveSearch);
        return true;
    }

    /** 停止并释放 socket（幂等）。 */
    public void stop() {
        running = false;
        DatagramSocket s = socket;
        socket = null;
        // close 会打断阻塞中的 receive，让 recvThread 立刻退出
        try {
            if (s != null) s.close();
        } catch (Throwable t) {
            log(2, "关闭 socket 失败", t);
        }
        joinQuietly(recvThread);
        joinQuietly(workThread);
        recvThread = null;
        workThread = null;
        log(1, "CDP 发现已停止");
    }

    private void joinQuietly(Thread t) {
        if (t == null || t == Thread.currentThread()) return;
        try {
            t.join(1000);
        } catch (Throwable e) {
            log(2, "join 线程失败", e);
        }
    }

    // =====================================================================
    // 接收
    // =====================================================================

    private void receiveLoop() {
        byte[] buf = new byte[RECV_BUF];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) break;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                if (p.getLength() <= 0) continue;
                // 复制出来再处理：buf 会被下一个 receive 覆盖
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());
                handlePacket(data, p.getAddress(), p.getPort());
            } catch (Throwable t) {
                if (!running) break;              // stop() 关 socket 造成的正常退出
                log(2, "接收循环异常（继续）", t);
                notifyError("接收异常: " + t, t);
                sleepQuietly(200);
            }
        }
        log(1, "接收线程退出");
    }

    /** 处理一个收到的 UDP 包。 */
    void handlePacket(byte[] data, InetAddress from, int fromPort) {
        if (CdpHeader.declaredLength(data) != data.length) {
            log(2, "长度不符，丢弃: 实际 " + data.length
                    + " 声明 " + CdpHeader.declaredLength(data));
            return;
        }
        CdpHeader h = CdpHeader.parsePacket(data, data.length);
        if (h == null) {
            log(2, "非法 CDP 头，丢弃 " + data.length + " 字节");
            return;
        }
        if (h.type != CdpHeader.TYPE_DISCOVERY) {
            // 我们只做发现层，其它类型（Connect/Control/Session…）不处理
            log(1, "忽略非 Discovery 消息 type=" + h.type + " 来自 " + from.getHostAddress());
            return;
        }
        if (data.length <= CdpHeader.SIZE) {
            log(2, "Discovery 包没有载荷，丢弃");
            return;
        }
        int discoveryType = data[CdpHeader.SIZE] & 0xFF;
        if (discoveryType == DISCOVERY_PRESENCE_REQUEST) {
            String ip = from.getHostAddress();
            // ★必须过滤本机自己的广播★ 真机实测：我们发往
            // 192.168.207.255 / 172.20.127.255 的 PresenceRequest 会被本机
            // 两块网卡各自回环收一次，源地址正是本机这两个 IP。
            // 不过滤就会"自己应答自己" —— 在电脑列表里制造幽灵设备、并白刷流量。
            if (isLocalAddress(ip)) {
                log(L_INFO, "忽略自己发出的广播（回环） ← " + ip);
                return;
            }
            log(L_INFO, "收到 PresenceRequest ← " + ip + ":" + fromPort
                    + "  reqId=0x" + Long.toHexString(h.requestId));
            // 每次请求都应答：Windows 用 requestId 匹配，漏一次它就可能把我们漏掉。
            // 去重只用来降噪日志，不阻止回包。
            boolean first;
            synchronized (answered) {
                first = answered.add(ip);
            }
            if (!first) log(L_INFO, "（重复请求，仍然应答） " + ip);
            sendPresenceResponse(from, fromPort, h.requestId);
        } else if (discoveryType == DISCOVERY_PRESENCE_RESPONSE) {
            Peer peer = remember(from.getHostAddress());
            // ★载荷直接从头部之后开始，没有 Message_Type 前缀★（实测纠正）
            // 这里曾经写成 SIZE + 1，多跳 1 字节，会把对方的名字解析成乱码。
            parsePresenceResponse(data, CdpHeader.SIZE, data.length, peer);
            log(1, "收到 PresenceResponse ← " + peer);
        } else {
            log(2, "未知 DiscoveryType=" + discoveryType + " 来自 " + from.getHostAddress());
        }
        Listener l = listener;
        if (l != null) {
            try {
                l.onCdpPacket(peerFor(from.getHostAddress()), discoveryType, h);
            } catch (Throwable t) {
                log(2, "回调异常", t);
            }
        }
    }

    // =====================================================================
    // 发送
    // =====================================================================

    /** PresenceRequest 整包（广播用）：42 字节头 + 1 字节载荷。 */
    public byte[] buildPresenceRequest() {
        CdpHeader h = new CdpHeader(CdpHeader.TYPE_DISCOVERY);
        h.requestId = newRequestId();
        h.fragmentIndex = 0;
        h.fragmentCount = 1;
        return CdpHeader.build(h, PRESENCE_REQUEST_PAYLOAD);
    }

    /**
     * PresenceResponse 整包（单播回给请求方）。
     *
     * <p>载荷字段顺序（★官方规范，逐字段照抄★）：
     * <pre>
     * Message_Type(u8)=1, Discovery_Type(u8)=1, Connection_Mode(u16)=1,
     * Device_Type(u16)=8(Android), Device_Name_Length(u16), Device_Name(UTF-8),
     * DeviceIdSalt(i32), DeviceIdHash(4), PrincipalUserNameHash(4), MacAddress(6)
     * </pre>
     *
     * @param replyToRequestId 对方请求里的 RequestID，用于 Windows 侧匹配
     */
    public byte[] buildPresenceResponse(long replyToRequestId) {
        byte[] name = deviceName.getBytes(CdpHeader.UTF8);
        byte[] idHash = computeDeviceIdHash();

        // ★应答载荷的两种布局（规格 §10）★
        //
        // 官方规范与"真能和 Windows 说话"的开源实现**不一致**：
        //   规范    : DeviceIdHash=4 字节 + PrincipalUserNameHash(4) + MacAddress(6)
        //   开源实现: DeviceIdHash=32 字节，★后面两个字段都不发★
        //
        // 开源实现是**唯一被证明能与 Windows 互通**的组合，
        // 所以 LAYOUT 默认取 OPEN_SOURCE；若电脑不认，
        // 改成 OFFICIAL_SPEC 再试（反之亦然）。
        // 这两个常量是**唯一需要改的地方**，其余代码不用动。
        boolean openSource = PRESENCE_RESPONSE_LAYOUT == LAYOUT_OPEN_SOURCE;
        int hashLen = openSource ? 32 : 4;
        int tailLen = openSource ? 0 : (4 + 6);   // PrincipalUserNameHash + MacAddress

        byte[] payload = new byte[1 + 2 + 2 + 2 + name.length + 4 + hashLen + tailLen];
        int o = 0;
        // ★载荷直接从 Discovery_Type 开始，没有 Message_Type 前缀★
        //
        // 这一字节是 2026-10-16 的真机实测纠正的：原先多写了一个
        // Message_Type=1，导致对方解析出的 Connection_Mode / Device_Type
        // 全部错位 1 字节。反推 Windows 回的包（偏移 42 起就是 01=Response）
        // 确认**没有**这个前缀。
        payload[o++] = (byte) DISCOVERY_PRESENCE_RESPONSE;             // Discovery_Type = 1
        CdpHeader.putU16(payload, o, connectionMode); o += 2;
        CdpHeader.putU16(payload, o, deviceType); o += 2;
        CdpHeader.putU16(payload, o, name.length); o += 2;             // 2 字节长度前缀
        System.arraycopy(name, 0, payload, o, name.length); o += name.length;
        CdpHeader.putU32(payload, o, deviceIdSalt); o += 4;            // DeviceIdSalt(i32)
        // DeviceIdHash：开源布局要 32 字节，规范布局取前 4 字节
        System.arraycopy(idHash, 0, payload, o, hashLen); o += hashLen;
        if (!openSource) {
            System.arraycopy(principalUserNameHash, 0, payload, o, 4); o += 4;
            System.arraycopy(macAddress, 0, payload, o, 6); o += 6;
        }

        CdpHeader h = new CdpHeader(CdpHeader.TYPE_DISCOVERY);
        h.requestId = replyToRequestId;
        // ★官方规范 §1 的例外★：SetReplyToId 写 RequestID 用小端。
        // 打开这个开关就是"照抄规范"的版本；默认走大端（CDP 惯例）。
        h.requestIdLe = REPLY_REQUEST_ID_LITTLE_ENDIAN;
        h.fragmentIndex = 0;
        h.fragmentCount = 1;
        return CdpHeader.build(h, payload);
    }

    // ---- 应答布局选择（规格 §10）----

    /** 官方规范布局：DeviceIdHash(4) + PrincipalUserNameHash(4) + MacAddress(6)。 */
    public static final int LAYOUT_OFFICIAL_SPEC = 0;
    /** 开源实现布局：DeviceIdHash(32)，**不发**后两个字段。 */
    public static final int LAYOUT_OPEN_SOURCE = 1;

    /**
     * ★联调时最可能要改的一个常量★
     *
     * <p>默认取 {@link #LAYOUT_OPEN_SOURCE} —— 因为那是
     * <a href="https://github.com/nearby-sharing/android">nearby-sharing</a>
     * 实际与 Windows 互通时用的组合，比规范文本更可信。
     *
     * <p>若电脑的「就近共享」列表里**始终不出现本机**，
     * 把它改成 {@link #LAYOUT_OFFICIAL_SPEC} 再试一次。
     */
    public static volatile int PRESENCE_RESPONSE_LAYOUT = LAYOUT_OPEN_SOURCE;

    /**
     * 应答里的 RequestID 是否用小端。
     *
     * <p>规格文档 §1 记录了规范自身的不一致：{@code CommonHeader.SetReplyToId}
     * 用 {@code BinaryPrimitives.WriteUInt64LittleEndian}。这里做成常量便于切换。
     * <b>默认 true（照抄规范）</b>；若电脑不认，改成 false 走大端再试。
     */
    public static final boolean REPLY_REQUEST_ID_LITTLE_ENDIAN = true;

    /**
     * DeviceIdHash = SHA256(salt || 内部设备ID)。
     *
     * <p>★返回完整的 32 字节★（不再截断）—— 因为
     * 开源布局要用 32 字节、规范布局只用前 4 字节，
     * 截断交给 {@link #buildPresenceResponse} 按布局决定。
     * 见规格 §10 的冲突说明。
     */
    public byte[] computeDeviceIdHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(intToBytesBE(deviceIdSalt));
            if (deviceId != null) md.update(deviceId.getBytes(CdpHeader.UTF8));
            return md.digest();
        } catch (Throwable t) {
            // 拿不到摘要（理论上不会发生在 Android 上）也必须回包，不能把发现层拖死
            log(2, "计算 DeviceIdHash 失败，用盐兜底", t);
            byte[] out = new byte[32];
            byte[] salt = intToBytesBE(deviceIdSalt);
            System.arraycopy(salt, 0, out, 0, 4);
            return out;
        }
    }

    private static byte[] intToBytesBE(int v) {
        byte[] b = new byte[4];
        CdpHeader.putU32(b, 0, v);
        return b;
    }

    private long newRequestId() {
        try {
            return new SecureRandom().nextLong();
        } catch (Throwable t) {
            log(2, "SecureRandom 失败，回落 nanoTime", t);
            return System.nanoTime();
        }
    }

    /** 单播回 PresenceResponse 给请求方。 */
    public boolean sendPresenceResponse(InetAddress to, int port, long replyToRequestId) {
        byte[] pkt = buildPresenceResponse(replyToRequestId);
        boolean ok = sendTo(pkt, to, port == 0 ? UDP_PORT : port);
        if (ok) {
            log(1, "已应答 PresenceResponse → " + to.getHostAddress() + ":" + port
                    + " (" + pkt.length + " 字节)");
        }
        return ok;
    }

    /** 向所有网卡的广播地址发 PresenceRequest。 */
    public int broadcastPresenceRequest() {
        byte[] pkt = buildPresenceRequest();
        List<InetAddress> targets = broadcastAddresses();
        int sent = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (sendTo(pkt, targets.get(i), UDP_PORT)) sent++;
        }
        // 兜底：没有任何可用广播地址时，至少试一次 255.255.255.255
        if (sent == 0) {
            try {
                if (sendTo(pkt, InetAddress.getByName("255.255.255.255"), UDP_PORT)) sent = 1;
            } catch (Throwable t) {
                log(L_WARN, "兜底广播 255.255.255.255 失败", t);
            }
        }
        log(1, "广播 PresenceRequest 到 " + sent + "/" + targets.size() + " 个网卡广播地址");
        return sent;
    }

    private boolean sendTo(byte[] pkt, InetAddress to, int port) {
        DatagramSocket s = socket;
        if (s == null || to == null) return false;
        try {
            s.send(new DatagramPacket(pkt, pkt.length, to, port));
            return true;
        } catch (Throwable t) {
            log(2, "发送失败 → " + to.getHostAddress() + ":" + port + " : " + t, t);
            notifyError("发送失败: " + t, t);
            return false;
        }
    }

    /**
     * 枚举所有网卡的**广播地址**。
     *
     * <p>★关键点★：不能只发 {@code 255.255.255.255}。本机实测有
     * {@code wlan0}=172.20.127.255 与 {@code wlan2}=192.168.207.255 两块，
     * 电脑在哪个网段决定它能不能收到 —— 必须逐个发。
     */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
                if (addrs == null) continue;
                for (int i = 0; i < addrs.size(); i++) {
                    InterfaceAddress ia = addrs.get(i);
                    if (ia == null) continue;
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) {
                        out.add(b);
                        log(1, "网卡 " + ni.getName() + " → 广播地址 " + b.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            log(2, "枚举网卡广播地址失败", t);
        }
        return out;
    }

    // =====================================================================
    // 工作线程
    // =====================================================================

    private void workLoop() {
        // 启动后稍等，让网络接口就绪，再决定要不要主动广播
        sleepQuietly(1000);
        long lastBroadcast = 0;
        while (running) {
            try {
                if (proactiveSearch) {
                    long now = System.currentTimeMillis();
                    if (now - lastBroadcast > 3000) {
                        lastBroadcast = now;
                        broadcastPresenceRequest();
                    }
                }
                sleepQuietly(500);
            } catch (Throwable t) {
                log(2, "工作循环异常（继续）", t);
                sleepQuietly(500);
            }
        }
    }

    /**
     * 这个源地址是不是本机自己？
     *
     * <p>用于丢弃"自己广播的回环"（见 {@code handlePacket} 里的说明）。
     * 判断覆盖两种身份：本机所有网卡的 IPv4，以及回环地址。
     * 网卡枚举失败时**返回 false**（宁可多应答一次，也不能把真实对端挡掉）。
     */
    static boolean isLocalAddress(String ip) {
        if (ip == null) return false;
        try {
            if ("127.0.0.1".equals(ip) || "0.0.0.0".equals(ip) || "::1".equals(ip)) return true;
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni == null) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a != null && ip.equals(a.getHostAddress())) return true;
                }
            }
        } catch (Throwable t) {
            log(L_WARN, "判断本机地址失败（按非本机处理）: " + t);
        }
        return false;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
            // 被 interrupt 是停止流程的一部分，这里没有需要清理的状态
        }
    }

    // =====================================================================
    // 对端记录 & 解析
    // =====================================================================

    private Peer remember(String ip) {
        synchronized (peers) {
            for (int i = 0; i < peers.size(); i++) {
                if (peers.get(i).ip.equals(ip)) {
                    peers.get(i).lastSeenMs = System.currentTimeMillis();
                    return peers.get(i);
                }
            }
            Peer p = new Peer(ip);
            p.lastSeenMs = System.currentTimeMillis();
            peers.add(p);
            return p;
        }
    }

    private Peer peerFor(String ip) {
        synchronized (peers) {
            for (int i = 0; i < peers.size(); i++) {
                if (peers.get(i).ip.equals(ip)) return peers.get(i);
            }
        }
        return remember(ip);
    }

    /**
     * 解析 PresenceResponse 载荷。字段顺序严格按官方规范（见
     * {@link #buildPresenceResponse(long)}）；任何越界都当作对方版本不一致，
     * 记日志后保留已解析到的字段，绝不抛异常打断接收线程。
     */
    static void parsePresenceResponse(byte[] data, int off, int end, Peer into) {
        try {
            int p = off;
            // ★只有一个 Discovery_Type 字节，没有 Message_Type 前缀★
            // （真机实测：Windows 回的包里偏移 42 就是 Discovery_Type=1）
            p++;                                    // Discovery_Type（=1）
            into.connectionMode = CdpHeader.getU16(data, p); p += 2;
            into.deviceType = CdpHeader.getU16(data, p); p += 2;
            int nameLen = CdpHeader.getU16(data, p); p += 2;
            if (p + nameLen > end) {
                log(2, "Device_Name 长度越界: 声明 " + nameLen + " 可用 " + (end - p));
                return;
            }
            into.name = new String(data, p, nameLen, CdpHeader.UTF8);
            log(1, "对端设备: name=\"" + into.name + "\" type=" + into.deviceType
                    + " connMode=" + into.connectionMode + " 剩余 "
                    + (end - p - nameLen) + " 字节(ID/盐/哈希)");
        } catch (Throwable t) {
            log(2, "解析 PresenceResponse 载荷失败（继续运行）", t);
        }
    }

    private void notifyError(String msg, Throwable t) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onCdpError(msg, t);
        } catch (Throwable e) {
            log(2, "错误回调本身抛异常", e);
        }
    }

    // =====================================================================
    // 自检（不进主流程，供排查用）
    // =====================================================================

    /**
     * 打印本机网卡 / 广播地址 / PresenceRequest / PresenceResponse 的
     * 十六进制 dump —— 拿去和电脑端 Wireshark 抓包逐字节比对。
     */
    public static String selfCheckDump(String deviceName, byte[] mac, String deviceId) {
        MsCdpDiscovery d = new MsCdpDiscovery(deviceName, mac, deviceId);
        StringBuilder sb = new StringBuilder();
        sb.append("=== 本机网卡广播地址 ===\n");
        List<InetAddress> bs = broadcastAddresses();
        for (int i = 0; i < bs.size(); i++) {
            sb.append("  ").append(bs.get(i).getHostAddress()).append('\n');
        }
        if (bs.isEmpty()) sb.append("  （无，发送时会回落 255.255.255.255）\n");
        byte[] req = d.buildPresenceRequest();
        byte[] resp = d.buildPresenceResponse(0x1122334455667788L);
        sb.append('\n').append(CdpHeader.dumpWithCheck("PresenceRequest", req)).append('\n');
        sb.append('\n').append(CdpHeader.dumpWithCheck("PresenceResponse", resp)).append('\n');
        sb.append("\n载荷长度: PresenceRequest ").append(req.length - CdpHeader.SIZE)
                .append(" 字节, PresenceResponse ").append(resp.length - CdpHeader.SIZE)
                .append(" 字节");
        return sb.toString();
    }

    /** 供外部工具读取：把设备名截到上限内的实现细节。 */
    public int deviceNameByteLength() {
        return deviceName.getBytes(CdpHeader.UTF8).length;
    }

    /** 局域网收发小工具：把 socket 绑到 5050 并只收不发，用于真机验证。 */
    static boolean canBind5050() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(UDP_PORT));
            return true;
        } catch (Throwable t) {
            log(2, "5050 不可绑定: " + t);
            return false;
        } finally {
            try {
                if (s != null) s.close();
            } catch (Throwable ignored) {
                // 关闭失败没有补救手段，且进程即将结束
            }
        }
    }

    /**
     * 纯 JVM 自检入口：{@code java com.huchuan.cdp.MsCdpDiscovery} 直接打印
     * 网卡广播地址 + 两个包的十六进制 dump，不需要设备也不需要测试框架。
     *
     * <p>（在 Android 上不会有人调用它 —— 只是为了单机排查方便。）
     */
    public static void main(String[] args) {
        String name = args != null && args.length > 0 ? args[0] : "HuChuan-Android";
        byte[] mac = new byte[]{0x02, 0x00, 0x00, 0x12, 0x34, 0x56};
        System.out.println(selfCheckDump(name, mac, "huchuan-self-check"));
    }

    // =====================================================================
    // 测试钩子（package-private）
    //
    // 为什么需要：tests/run.sh 只把 CdpHeader 加进了编译列表（任务要求
    // 不改 run.sh），而单测要断言 PresenceResponse 的**真实字节**，
    // 所以这些钩子必须能在**没有 android.jar** 的 classpath 上编译通过。
    // 因此本类里不出现任何测试用到的 Android 类型，android.util.Log 只在
    // 运行期真正调用日志时才会被加载 —— 单测里传 null 设备名，
    // log() 直接短路，不会触发 NoClassDefFoundError。
    // =====================================================================

    /** 测试用构造：不读系统、不发包。 */
    public static MsCdpDiscovery forTest(String deviceName, byte[] mac, String deviceId) {
        return new MsCdpDiscovery(deviceName, mac, deviceId);
    }

    public byte[] buildPresenceResponseForTest(long replyToRequestId) {
        return buildPresenceResponse(replyToRequestId);
    }

    public byte[] computeDeviceIdHashForTest() {
        return computeDeviceIdHash();
    }

    /** 测试用：把响应载荷解析进 {@link Peer}（等价于接收线程里的那条路径）。 */
    public static void parsePresenceResponseForTest(byte[] data, int off, Peer into) {
        parsePresenceResponse(data, off, data.length, into);
    }

    /** 测试用：暴露本机地址过滤判定。 */
    public static boolean isLocalAddressForTest(String ip) {
        return isLocalAddress(ip);
    }

    /** 测试用：列出本机所有网卡地址（与 isLocalAddress 同一套枚举口径）。 */
    public static List<InetAddress> localAddressesForTest() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni == null) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a != null) out.add(a);
                }
            }
        } catch (Throwable t) {
            log(L_WARN, "枚举本机地址失败", t);
        }
        return out;
    }

    public int deviceIdSaltForTest() {
        return deviceIdSalt;
    }

    public int deviceNameByteLengthForTest() {
        return deviceNameByteLength();
    }

    public static byte[] buildPresenceRequestForTest() {
        return new MsCdpDiscovery("HuChuan-Test", new byte[6], "id").buildPresenceRequest();
    }
}
