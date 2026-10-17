package com.huchuan.cdp;

import android.content.Context;
import android.util.Log;

import com.huchuan.core.DeviceName;

/**
 * MS-CDP 发现层的**进程级单例持有者**。
 *
 * <h3>为什么要这个类</h3>
 *
 * {@link MsCdpDiscovery} 自己不会去读设备名 / MAC / 设备 ID —— 这些都从构造
 * 参数进来。但 {@link ReceiverService#onStartCommand} 是**在 UI 线程**被
 * framework 调用的，那里既不该读 Settings（跨进程 IPC）、也不该去碰网卡。
 * 所以把"组装参数 + 起停 + 兜异常"这三件事收拢到这里，让挂载点只剩一行。
 *
 * <h3>为什么必须是单例</h3>
 *
 * UDP 5050 是**独占端口**（虽然设了 SO_REUSEADDR，两份实例仍会互相抢包）。
 * 而 {@link ReceiverService} 会被反复 start（磁贴连点、系统重启服务），
 * 每次 new 一个就会泄漏一份 socket + 两条线程。
 * 单例 + {@link #startIfNeeded} 的幂等判断从源头掐掉这个问题。
 *
 * <h3>生命周期铁律（P114 同类问题）</h3>
 *
 * <b>开关开着才跑，开关关了必须停</b>。本类的 {@link #stop} 是幂等的，
 * 由 {@code ReceiverService.onDestroy()} 调用 —— 那是服务所有退出路径
 * （用户关磁贴 / 定时到点 / 系统杀服务）的**唯一汇合点**。
 * 漏掉这一处，用户关了开关、电脑却还能看见本机。
 *
 * <p>★本类不持有 Activity / Service 引用★：传进来的 Context 一律先过
 * {@code getApplicationContext()}，避免 Service 被销毁后留着泄漏。
 *
 * <p>纯 Java，无 lambda / 无第三方依赖 / Java 8 语法。
 */
public final class CdpDiscoveryManager {

    private static final String TAG = "HuChuan.Cdp";

    /**
     * 是否让 CDP 主动向外广播 PresenceRequest。
     *
     * <p>★★ 2026-10-17 实测改为 <b>true</b> ★★
     *
     * <p>原先写 false 的理由是"Windows 自己会周期性广播，我们应答即可"。
     * **这个假设被真机实测推翻了**：
     *
     * <pre>
     * 电脑（Lenovo，Win10/11）监听 UDP 5050，但 80 秒内一个广播包都没发。
     * 而参考实现（nearby-sharing）的 RunPresenceSendLoop 是
     *   while(true) { SendPresenceRequest(); sleep(500ms); }
     * —— 它【从不被动等待】，而是自己不停主动问。
     * </pre>
     *
     * <p>也就是说 **Windows 的"就近共享"是被动的**：
     * 它只应答别人的 PresenceRequest，不会主动来找我们。
     * 因此主动方必须是我们，否则双方都在等对方，永远发现不了。
     *
     * <p>实测证据：我们主动发 PresenceRequest（载荷 0x00），
     * 电脑**每次都回** 131 字节 PresenceResponse（设备名 "Lenovo"）。
     */
    private static final boolean PROACTIVE_SEARCH = true;

    /** 单例实例；null = 从未启动过。 */
    private static volatile MsCdpDiscovery instance;

    /** 防并发：onStartCommand 理论上可能重入。 */
    private static final Object LOCK = new Object();

    private CdpDiscoveryManager() {}

    /**
     * 开关开着时调用：确保 CDP 发现**正好跑着一份**。
     *
     * <p>幂等 —— 已在运行就直接返回，不重建、不重绑端口。
     *
     * <p>★绝不上抛★：CDP 只是"附加能力"，小米互传那条链路不能因为它挂掉。
     * 绑定失败（5050 被别的 App 占用等）只记日志并返回 false。
     *
     * @param ctx 任意 Context（内部会取 applicationContext）
     * @return true = 现在正在运行；false = 启动失败（原因已记日志）
     */
    public static boolean startIfNeeded(Context ctx) {
        if (ctx == null) {
            Log.w(TAG, "startIfNeeded 收到 null Context，跳过 CDP 发现");
            return false;
        }
        synchronized (LOCK) {
            MsCdpDiscovery cur = instance;
            if (cur != null && cur.isRunning()) {
                Log.i(TAG, "CDP 发现已在运行，跳过重复启动");
                return true;
            }
            try {
                // ★applicationContext★ —— 绝不持有 Service/Activity 引用（内存泄漏）
                Context app = ctx.getApplicationContext();
                if (app == null) app = ctx;

                String name = DeviceName.get(app);
                String mac = com.huchuan.core.P2pManager.readInterfaceMac("wlan0");
                if (mac == null || mac.isEmpty()) {
                    // MAC 只用于对端去重，拿不到不是致命错误 ——
                    // 宁可留空也不能编一个假地址（照 P2pManager 的既有口径）。
                    Log.w(TAG, "读不到 wlan0 MAC，CDP 用空 MAC 继续（仅影响对端去重）");
                }
                String deviceId = deviceIdOf(app);

                MsCdpDiscovery d = new MsCdpDiscovery(name, parseMac(mac), deviceId);
                d.setProactiveSearch(PROACTIVE_SEARCH);
                d.setListener(new MsCdpDiscovery.Listener() {
                    @Override public void onCdpPacket(MsCdpDiscovery.Peer peer,
                                                      int discoveryType,
                                                      CdpHeader header) {
                        // 只记日志，不动 UI、不弹通知 ——
                        // 发现层的每一次收发都弹通知会刷爆通知栏（P090 的教训）。
                        Log.i(TAG, "CDP 包 type=" + header.type
                                + " discoveryType=" + discoveryType
                                + " ← " + (peer == null ? "?" : peer.toString()));
                    }

                    @Override public void onCdpError(String message, Throwable t) {
                        // 发现层内部错误（收发异常）不影响主流程，记日志即可
                        Log.w(TAG, "CDP 错误: " + message, t);
                    }
                });

                boolean ok = d.start();
                if (!ok) {
                    // 端口被占 / 权限不足：★不崩★ —— CDP 是可降级能力
                    Log.w(TAG, "CDP 发现启动失败（UDP "
                            + MsCdpDiscovery.UDP_PORT + " 不可用），"
                            + "小米互传接收不受影响");
                    instance = null;
                    return false;
                }
                instance = d;
                // ★把当前应答布局打进日志★ —— 联调时一眼就能确认"现在跑的是哪种布局"，
                // 不用去翻源码。若电脑列表里不出现本机，按规格 §10 换成另一种再试。
                String layout = com.huchuan.cdp.MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT
                        == com.huchuan.cdp.MsCdpDiscovery.LAYOUT_OPEN_SOURCE
                        ? "开源(IdHash32,无尾部字段)" : "官方规范(IdHash4+尾部10字节)";
                Log.i(TAG, "CDP 发现已随接收开关启动: 设备名=\"" + name
                        + "\" 主动广播=" + PROACTIVE_SEARCH
                        + " 应答布局=" + layout
                        + " (DeviceIdHash=" + (com.huchuan.cdp.MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT
                            == com.huchuan.cdp.MsCdpDiscovery.LAYOUT_OPEN_SOURCE ? 32 : 4) + "字节)"
                        + " 监听 0.0.0.0:" + com.huchuan.cdp.MsCdpDiscovery.UDP_PORT);
                return true;
            } catch (Throwable t) {
                // ★必须 catch Throwable★ ——
                // 这里在 onStartCommand 的调用栈上，任何冒泡都会让
                // ReceiverService 崩掉，把"附加能力"的故障放大成主功能故障。
                Log.e(TAG, "启动 CDP 发现异常（已吞掉，不影响接收）", t);
                instance = null;
                return false;
            }
        }
    }

    /**
     * 开关关闭 / 服务销毁时调用：停掉 CDP 发现。
     *
     * <p>幂等，可以重复调。★绝不上抛★。
     */
    public static void stop() {
        synchronized (LOCK) {
            MsCdpDiscovery d = instance;
            instance = null;
            if (d == null) {
                Log.i(TAG, "CDP 发现未在运行，无需停止");
                return;
            }
            try {
                d.stop();
                Log.i(TAG, "CDP 发现已停止");
            } catch (Throwable t) {
                // 停止失败没有补救手段，且进程/服务即将退出；
                // 但绝不能让它冒泡打断 onDestroy 的其它收尾动作。
                Log.e(TAG, "停止 CDP 发现异常（已吞掉）", t);
            }
        }
    }

    /** 供 UI / 诊断查询：CDP 发现当前是否在跑。 */
    public static boolean isRunning() {
        MsCdpDiscovery d = instance;
        return d != null && d.isRunning();
    }

    /**
     * 内部设备 ID。
     *
     * <p>官方规范里 DeviceId 用来对端去重，**不参与鉴权**，
     * 所以取一个"本机稳定、卸载重装才变"的值即可。
     * 这里用 {@code Settings.Secure.ANDROID_ID}：不需要额外权限，
     * 且正是"同一台设备同一安装"的语义。
     * 拿不到时回落到设备名 —— 绝不返回 null（构造器会兜，但显式更清楚）。
     */
    private static String deviceIdOf(Context app) {
        try {
            String id = android.provider.Settings.Secure.getString(
                    app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id != null && !id.trim().isEmpty()) return id.trim();
        } catch (Throwable t) {
            // 读 Settings 失败不该影响发现层，回落到设备名
            Log.w(TAG, "读 ANDROID_ID 失败，回落设备名: " + t);
        }
        String n = DeviceName.get(app);
        return n == null ? "huchuan" : n;
    }

    /**
     * 把 {@code "AA:BB:CC:DD:EE:FF"} 解析成 6 字节。
     *
     * <p>{@code MsCdpDiscovery} 的构造器接受 null（会填 0），但显式解析更可控。
     * 任何格式异常都返回 null，由构造器兜底 —— 绝不让格式问题冒泡。
     */
    static byte[] parseMac(String mac) {
        if (mac == null) return null;
        try {
            String[] parts = mac.trim().split(":");
            if (parts.length != 6) return null;
            byte[] out = new byte[6];
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "MAC 解析失败（\"  " + mac + "\"），按空 MAC 处理: " + t);
            return null;
        }
    }
}
