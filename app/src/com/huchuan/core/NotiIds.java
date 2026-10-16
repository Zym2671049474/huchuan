package com.huchuan.core;

import java.util.HashMap;

/**
 * 通知 ID 分配器 —— 照小米互传 `g3/r` 的做法（P109）。
 *
 * ## 小米互传怎么做（反编译证据）
 *
 * ```java
 * private final HashMap<String,Integer> f = new HashMap<>();  // taskId → 通知ID
 * private int g = 0x7e3;                                      // 起始 ID
 *
 * // g3/r.f(ctx, task, mission)
 * int id = this.g;
 * this.g = id + 1;                       // 每来一个新任务 +1
 * this.f.put(task.taskId, Integer.valueOf(id));   // 记住 taskId → id
 * ```
 *
 * 用的时候先查 `f.get(taskId)`，**查到就复用同一个 ID**：
 * ```java
 * // g3/r.c(ctx, taskId, fileInfos)
 * Integer id = f.get(taskId);
 * if (id == null) return;                // 没登记过就不动
 * nm.notify(id.intValue(), builder.build());
 * ```
 *
 * ## 为什么要这样做（而不是写死 ID）
 *
 * 1. **同任务原地更新**：进度从 10% → 90% 用**同一个** ID，
 *    系统是"更新这条通知"，不是"再插一条" —— 不会闪烁、不会堆积。
 * 2. **多任务并存**：两个任务拿两个 ID，互不覆盖。
 *    我们原来写死 `NOTI_ID = 1002`，第二次传输会**盖掉**第一次的通知。
 * 3. **startId 用 0x7e3 起步**：避开系统/其他组件的低 ID 段。
 *
 * ## 我们的做法
 *
 * 保留 1001~1004 这几个"固定角色"的通知（常驻、询问、完成），
 * 传输进度按 taskId 动态分配 —— 从 {@link #BASE} 起，逐个 +1。
 */
public final class NotiIds {

    /** 动态分配的起始 ID（照小米互传 0x7e3 = 2019）。 */
    public static final int BASE = 0x7e3;

    /** taskId → 通知 ID */
    private static final HashMap<String, Integer> MAP = new HashMap<>();
    private static int next = BASE;

    /**
     * 取这个 taskId 对应的通知 ID。
     *
     * 已有就返回原来的（**原地更新**），没有才分配新的。
     * 与小米互传 `g3/r.f` + `g3/r.c` 的 get/put 语义一致。
     */
    public static synchronized int of(String taskId) {
        if (taskId == null || taskId.isEmpty()) return BASE;
        Integer id = MAP.get(taskId);
        if (id != null) return id;
        int v = next++;
        // 分配过多时回绕，避免 ID 无限增长（小米互传没做，但更稳）
        if (next > BASE + 500) next = BASE;
        MAP.put(taskId, Integer.valueOf(v));
        return v;
    }

    /** 任务结束后回收（小米互传在 clear()/cancelAll() 时清空整张表）。 */
    public static synchronized void release(String taskId) {
        if (taskId != null) MAP.remove(taskId);
    }

    /** 清空全部分配（用于 cancelAll 场景）。 */
    public static synchronized void clear() {
        MAP.clear();
        next = BASE;
    }

    private NotiIds() {}
}
