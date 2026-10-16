package com.huchuan.core;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 通知渠道与分组的**统一出处**。
 *
 * 全部对照小米互传 4.12.0 反编译结果实现（P109）。
 * 反编译证据：
 *   · 渠道定义：`e3/b.b(Context, NotificationManager)`
 *   · 分组：    `g3/r` 里 `setGroup("mishare_persist")`
 *   · 渠道 id： `mishare_files_notification`
 *               `mishare_files_notification_low_priority`
 *               `mishare_persist`
 *               `mishare_receive_files_notification`
 *
 * 小米互传的渠道划分思路（值得抄）：
 *   1. **传输类渠道全部静音**（setSound(null) + enableVibration(false)
 *      + enableLights(false)），因为传输进度会**高频刷新**，
 *      每次都响会疯掉。真正需要提醒的是「有人要发文件给你」。
 *   2. **同一组渠道共用名字/描述**（都取同一个字符串资源），
 *      靠重要性区分：IMPORTANCE_HIGH(4) 给需要立刻看到的状态，
 *      IMPORTANCE_LOW(2) 给纯进度。
 *   3. **不显示角标**（setShowBadge(false)）—— 传输不是"未读消息"。
 *   4. 锁屏可见性设为 1（VISIBILITY_PRIVATE）。
 */
public final class Noti {

    /** 接收：进度 / 进行中（低频、静音）。 */
    public static final String CH_RECV = "huchuan_recv";
    /** 接收：有人要发文件给你，需要你点（会响）。 */
    public static final String CH_ASK = "huchuan_ask";
    /** 发送：进度 / 进行中（低频、静音）。 */
    public static final String CH_SEND = "huchuan_send";
    /** 发送 / 接收的**完成**通知（静音，但独立成渠道，方便用户单独关）。 */
    public static final String CH_DONE = "huchuan_done";

    /**
     * 常驻通知（"广播中，等待发送方…"）的分组名。
     *
     * 小米互传用 `mishare_persist` 把常驻通知单独归组，
     * 这样它不会和传输任务混在一起，用户折叠分组时也只折叠任务。
     */
    public static final String GROUP = "huchuan_group";

    /** 一次性把渠道建好（幂等，可重复调用）。 */
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ① 询问渠道：**唯一会响的**。
        //    小米互传没有对应渠道（它用 mishare_receive_files_notification 弹 Activity），
        //    但用户要求用通知询问，所以这里做成 HIGH + 响铃 + 震动 + 锁屏公开。
        create(nm, CH_ASK, "接收请求", NotificationManager.IMPORTANCE_HIGH, true);

        // ② 传输进度渠道：静音（小米互传 mishare_files_notification = IMPORTANCE_HIGH(4)
        //    但也 setSound(null)+禁震动，说明"高重要性"只为置顶，不为响铃）。
        create(nm, CH_RECV, "互传通知", NotificationManager.IMPORTANCE_LOW, false);
        create(nm, CH_SEND, "互传通知", NotificationManager.IMPORTANCE_LOW, false);

        // ③ 完成渠道：同样是低重要性静音。
        //    小米互传的 low_priority 渠道 = IMPORTANCE_LOW(2)。
        create(nm, CH_DONE, "传输完成", NotificationManager.IMPORTANCE_LOW, false);
    }

    private static void create(NotificationManager nm, String id, String name,
                               int importance, boolean alerting) {
        if (nm.getNotificationChannel(id) != null) return;   // 已建过就别动（保留用户设置）
        NotificationChannel c = new NotificationChannel(id, name, importance);
        if (!alerting) {
            // ★静音三连★ —— 照小米互传：
            //   setSound(null,null) / enableVibration(false) / enableLights(false)
            // 进度通知会高频刷新，响了会疯。
            c.setSound(null, null);
            c.enableVibration(false);
            c.enableLights(false);
            // ★不显示角标★（小米互传 setShowBadge(false)）
            c.setShowBadge(false);
        } else {
            c.enableVibration(true);
            c.setShowBadge(true);
        }
        c.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);  // = 1，照抄
        c.setDescription("互传文件发送和接收通知");
        nm.createNotificationChannel(c);
    }

    private Noti() {}
}
