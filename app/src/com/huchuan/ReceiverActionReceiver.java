package com.huchuan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收「接受 / 拒绝」通知按钮的点击。
 *
 * 只负责把结果写进 {@link ReceiverService#DECISION}，
 * 真正的 status 回包由 ReceiverService 的等待循环发出。
 */
public class ReceiverActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ACCEPT = "com.huchuan.action.ACCEPT";
    public static final String ACTION_REJECT = "com.huchuan.action.REJECT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_ACCEPT.equals(a)) {
            Log.i(ReceiverService.TAG, "用户点了「接收」");
            ReceiverService.DECISION.set(1);
        } else if (ACTION_REJECT.equals(a)) {
            Log.i(ReceiverService.TAG, "用户点了「拒绝」");
            ReceiverService.DECISION.set(2);
        }
    }
}
