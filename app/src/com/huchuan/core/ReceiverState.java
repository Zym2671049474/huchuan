package com.huchuan.core;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收开关的全局状态 + 空闲计时。
 *
 * 谁在用：
 *   · {@link com.huchuan.TileService} —— 磁贴开关，读写这里
 *   · {@link com.huchuan.ReceiverService} —— 传输进行中要"续命"，传输完要立刻关
 *
 * 行为约定（用户确认过的）：
 *   · 磁贴开启 → 默认 10 分钟后自动关闭
 *   · 正在传输时**不关**（空闲计时暂停）
 *   · 传输完成 → 立即关闭（不等满 10 分钟）
 */
public final class ReceiverState {

    private static final String TAG = "HuChuan.RecvState";

    /** 磁贴状态变化广播（磁贴自身监听，用来刷新图标）。 */
    public static final String ACTION_STATE =
            "com.huchuan.action.RECEIVER_STATE";
    public static final String EXTRA_RUNNING = "running";

    /** 默认存活时长：10 分钟。 */
    public static final long DEFAULT_TTL_MS = 10L * 60 * 1000;
    /** 永久开启（不自动关）。 */
    public static final long PERMANENT_TTL_MS = -1L;

    private static volatile boolean running;
    private static volatile long deadline;      // 0 = 不限时（永久）/ 未启动
    private static volatile boolean permanent;
    /** 正在传输的会话数：>0 时倒计时暂停。 */
    private static volatile int activeTransfers;
    /** 传输暂停倒计时时，剩余的时间（毫秒）。 */
    private static volatile long pausedRemaining;

    // ---------------- 持久化（P114） ----------------
    //
    // ★为什么必须持久化★
    //
    // 原来这些全是**内存里的 static** —— 进程一死（被 Thanox 杀、安装更新、
    // 内存回收）就全部归零，`running` 变回 false。
    // 而 ReceiverService.onStartCommand 里写着：
    //     if (!isRunning()) enable(...)     // "进了服务就说明开关是开的"
    // 这个假设是**错的**：服务被重新拉起时，内存态已经是 false，
    // 于是它**自动把开关打开并开始广播** —— 用户看到的就是
    // "本机没开接收开关，小米15却扫描到了本机"。
    //
    // 有了持久化，服务重启后能恢复"用户上次到底开没开"：
    //   · 之前是开的且没过期 → 继续广播，并且**保留原来的倒计时**
    //   · 之前是关的 / 已过期 → 坚决不广播，立刻退出
    //
    // 注意：activeTransfers / pausedRemaining **不持久化** ——
    // 传输不可能跨进程存活，持久化它们只会制造假状态。
    private static final String PREF = "huchuan_receiver_state";
    private static final String K_RUNNING = "running";
    private static final String K_PERMANENT = "permanent";
    private static final String K_DEADLINE = "deadline";

    private ReceiverState() {}

    private static void persist(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(K_RUNNING, running)
                    .putBoolean(K_PERMANENT, permanent)
                    .putLong(K_DEADLINE, deadline)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "持久化接收状态失败: " + t);
        }
    }

    /**
     * 从磁盘恢复状态（**只在 ReceiverService.onStartCommand 里调用**）。
     *
     * 为什么不放别处：内存态是"当前进程的真实状态"，磁盘态是
     * "用户上次的意愿"。只有在"服务即将决定要不要广播"这一刻，
     * 才需要拿磁盘态去校准。若让 UI 也去 restore，会出现
     * "磁贴显示开着、其实没有服务在广播"的假象。
     */
    public static synchronized void restore(Context ctx) {
        if (ctx == null) return;
        try {
            android.content.SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            running = p.getBoolean(K_RUNNING, false);
            permanent = p.getBoolean(K_PERMANENT, false);
            deadline = p.getLong(K_DEADLINE, 0);
            activeTransfers = 0;
            pausedRemaining = 0;
            Log.i(TAG, "恢复接收状态: running=" + running
                    + " permanent=" + permanent + " deadline=" + deadline);
        } catch (Throwable t) {
            Log.w(TAG, "恢复接收状态失败: " + t);
        }
    }

    public static boolean isRunning() { return running; }

    public static boolean isPermanent() { return permanent; }

    /** 剩余毫秒数；永久、未开启、或正在传输（已暂停）返回 -1。 */
    public static long remainingMs() {
        if (!running || permanent) return -1;
        if (activeTransfers > 0) return -1;   // 暂停中，不显示倒计时
        if (deadline <= 0) return -1;
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    /** 打开接收开关。ttlMs 传 {@link #PERMANENT_TTL_MS} 表示不限时。 */
    public static synchronized void enable(Context ctx, long ttlMs) {
        enable(ctx, ttlMs, true);
    }

    /**
     * @param resetTimer false 表示"只确保它在跑，别动已经算好的倒计时"。
     *
     * 为什么需要：ReceiverService 被系统回收后重启时也会调到这里。
     * 若每次都重置成 10 分钟，用户会得到一个**永远不过期**的开关 ——
     * 与"默认 10 分钟自动关"的约定不符（实测重启时会看到
     * 开→关→开 的抖动，倒计时被反复清零）。
     */
    public static synchronized void enable(Context ctx, long ttlMs, boolean resetTimer) {
        boolean wasRunning = running;
        running = true;
        permanent = (ttlMs == PERMANENT_TTL_MS);
        if (!resetTimer && wasRunning && deadline > 0 && !permanent) {
            Log.i(TAG, "接收开关已在运行，保留剩余倒计时");
        } else {
            deadline = permanent ? 0 : System.currentTimeMillis() + ttlMs;
        }
        activeTransfers = 0;
        pausedRemaining = 0;
        Log.i(TAG, "接收开关打开" + (permanent ? "（不限时）" : "，定时 " + (ttlMs / 60000) + " 分钟"));
        persist(ctx);
        broadcast(ctx);
    }

    /** 关闭接收开关。 */
    public static synchronized void disable(Context ctx) {
        if (!running) { deadline = 0; persist(ctx); return; }
        running = false;
        permanent = false;
        deadline = 0;
        activeTransfers = 0;
        pausedRemaining = 0;
        Log.i(TAG, "接收开关关闭");
        persist(ctx);
        broadcast(ctx);
    }

    /**
     * 一次传输开始 —— **真正暂停**倒计时。
     *
     * 上一版只做了 activeTransfers++，deadline 没动，而 isExpired() 只看 deadline，
     * 结果 10 分钟一到照样关 —— 与"10分钟没传完就等传完再关"正好相反（P071）。
     * 现在把剩余时间存进 pausedRemaining，倒计时归零，超时判断不再成立。
     */
    public static synchronized void beginTransfer() {
        if (activeTransfers == 0) {
            // 第一笔传输：暂停倒计时，记住还剩多久
            pausedRemaining = (running && !permanent && deadline > 0)
                    ? Math.max(0, deadline - System.currentTimeMillis())
                    : 0;
            deadline = 0;
            Log.i(TAG, "传输开始 → 暂停倒计时（剩余 " + (pausedRemaining / 1000) + "s）");
        } else {
            Log.i(TAG, "又有传输开始（并发 " + (activeTransfers + 1) + "）");
        }
        activeTransfers++;
    }

    /**
     * 一次传输结束。
     *
     * 规则（用户确认）：**开一次必须等满 10 分钟**。
     * 传输只是"暂停"这段计时，不是"提前结束"这段计时。
     * 所以这里恢复剩余倒计时继续走，而不是立刻关。
     * （上一版传完就 disable()，开 10 秒传完就关了，不符合要求 —— P071）
     */
    public static synchronized void endTransfer(Context ctx) {
        if (activeTransfers > 0) activeTransfers--;
        Log.i(TAG, "传输结束（剩余活跃 " + activeTransfers + "）");
        if (activeTransfers == 0 && running && !permanent) {
            long left = Math.max(0, pausedRemaining);
            deadline = left > 0 ? System.currentTimeMillis() + left
                                : System.currentTimeMillis() + DEFAULT_TTL_MS;
            pausedRemaining = 0;
            Log.i(TAG, "传输结束 → 恢复倒计时，还剩 " + (left / 1000) + "s");
            broadcast(ctx);
        }
    }

    /** 是否已超时（该关了）。永久模式、传输中都返回 false。 */
    public static boolean isExpired() {
        if (!running || permanent) return false;
        if (activeTransfers > 0) return false;  // 传输中永不超时
        return deadline > 0 && System.currentTimeMillis() >= deadline;
    }

    /** 传输进行中？ */
    public static boolean inTransfer() { return activeTransfers > 0; }

    /** 把"该关了"这件事落实（返回是否真的关了）。 */
    public static synchronized boolean enforceExpiry(Context ctx) {
        if (isExpired()) { disable(ctx); return true; }
        return false;
    }

    private static void broadcast(Context ctx) {
        try {
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(ctx.getPackageName());
            i.putExtra(EXTRA_RUNNING, running);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "广播状态失败: " + t);
        }
    }
}
