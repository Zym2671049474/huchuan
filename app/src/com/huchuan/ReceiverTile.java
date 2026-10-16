package com.huchuan;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import com.huchuan.core.ReceiverState;

/**
 * 「互传接收」磁贴。
 *
 * 行为（用户确认过）：
 *   · 点一下开 → 只弹一个悬浮提示，**不**挂常驻通知
 *   · 默认 10 分钟后自动关闭
 *   · 正在传输 → 不关；传输完成 → 立即关
 *   · 长按磁贴 → 打开设置页选「10 分钟 / 永久」
 *
 * 注意：{@link TileService} **没有**长按回调（javap 验证过），
 * 长按要另建一个 Activity 并在 manifest 声明
 * {@code android.service.quicksettings.action.QS_TILE_PREFERENCES}。
 */
public class ReceiverTile extends TileService {

    private static final String TAG = "HuChuan.Tile";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver watcher;

    /** 每秒刷新一次剩余时间，让磁贴副标题能倒计时。 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (ReceiverState.enforceExpiry(ReceiverTile.this)) {
                // 超时被关掉了，状态已经广播，这里只刷新自己
            }
            refresh();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.i(TAG, "onStartListening");

        // 监听状态变化（自己开的/关的，或服务那边传完自动关的）
        watcher = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { refresh(); }
        };
        IntentFilter f = new IntentFilter(ReceiverState.ACTION_STATE);
        try {
            registerReceiver(watcher, f, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            // 老系统没有这个重载
            registerReceiver(watcher, f);
        }

        refresh();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000L);
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        ui.removeCallbacks(tick);
        if (watcher != null) {
            try { unregisterReceiver(watcher); } catch (Throwable ignored) {}
            watcher = null;
        }
    }

    /** 防抖：同一次点击可能被系统重复投递（见 P076）。 */
    private long lastClickMs;

    @Override
    public void onClick() {
        super.onClick();
        long t = System.currentTimeMillis();
        if (t - lastClickMs < 600) {
            Log.i(TAG, "onClick 忽略（防抖 " + (t - lastClickMs) + "ms）");
            return;
        }
        lastClickMs = t;
        boolean now = ReceiverState.isRunning();
        Log.i(TAG, "onClick，当前=" + now);
        if (now) {
            ReceiverState.disable(this);
            stopService(new Intent(this, ReceiverService.class));
            toast("接收已关闭");
        } else {
            ReceiverState.enable(this, ReceiverState.DEFAULT_TTL_MS);
            startReceiver();
            toast("接收已开启（10 分钟后自动关闭）");
        }
        refresh();
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    private void startReceiver() {
        try {
            Intent i = new Intent(this, ReceiverService.class);
            i.setAction(ReceiverService.ACTION_START);
            startForegroundService(i);
        } catch (Throwable t) {
            Log.e(TAG, "启动接收服务失败", t);
            toast("启动失败：" + t.getMessage());
        }
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    /** 把当前状态画到磁贴上。 */
    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = ReceiverState.isRunning();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        try {
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_transfer));
        } catch (Throwable ignored) {}

        String sub;
        if (!on) {
            sub = "点击开启接收";
        } else if (ReceiverState.isPermanent()) {
            sub = "接收中 · 不限时";
        } else if (ReceiverState.inTransfer()) {
            // 传输中：倒计时已暂停，显示"完成后再走完剩余时间"
            sub = "接收中 · 传输中（暂停计时）";
        } else {
            long ms = ReceiverState.remainingMs();
            if (ms < 0) {
                sub = "接收中";
            } else {
                long sec = Math.max(0, ms / 1000);
                sub = String.format(java.util.Locale.ROOT,
                        "接收中 · 还剩 %d:%02d", sec / 60, sec % 60);
            }
        }
        t.setSubtitle(sub);
        t.setLabel("互传");
        t.updateTile();
    }

    /** 供设置页调用：开成永久。 */
    public static void enablePermanent(Context c) {
        ReceiverState.enable(c, ReceiverState.PERMANENT_TTL_MS);
        Intent i = new Intent(c, ReceiverService.class);
        i.setAction(ReceiverService.ACTION_START);
        c.startForegroundService(i);
    }

    /** 供设置页调用：开成 10 分钟。 */
    public static void enableTimed(Context c) {
        ReceiverState.enable(c, ReceiverState.DEFAULT_TTL_MS);
        Intent i = new Intent(c, ReceiverService.class);
        i.setAction(ReceiverService.ACTION_START);
        c.startForegroundService(i);
    }

    /** 请求系统刷新磁贴（设置页改完参数后调用）。 */
    public static void requestRefresh(Context c) {
        try {
            TileService.requestListeningState(c,
                    new ComponentName(c, ReceiverTile.class));
        } catch (Throwable ignored) {}
    }
}
