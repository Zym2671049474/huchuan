package com.huchuan.core;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 触发分享时自动打开蓝牙 / 定位（P134）。
 *
 * <h3>为什么需要这个</h3>
 *
 * 蓝牙或定位没开时，分享卡片能打开、扫描能启动，但**一个设备都收不到**。
 * 用户看到的是"一直在搜索…"，完全不知道该去开什么。之前只做了文字提示
 * （P133），但提示归提示，用户还是得自己跳出去开、再跳回来。
 *
 * <h3>能力分层（这是本类存在的核心原因）</h3>
 *
 * Android 把"改系统开关"卡得很死，普通 App 和 root 能做的事差一个量级：
 *
 * <table border="1">
 *   <tr><th>开关</th><th>普通 App</th><th>有 root</th></tr>
 *   <tr><td>蓝牙</td>
 *       <td>只能弹 {@link BluetoothAdapter#ACTION_REQUEST_ENABLE} 系统对话框，
 *           要用户点一下"允许"</td>
 *       <td>{@code svc bluetooth enable} 直接开</td></tr>
 *   <tr><td>定位</td>
 *       <td><b>完全做不到</b> —— 没有公开 API。连跳设置页也不做（用户反馈
 *           太突兀），只给一句文字提示，让用户从控制中心自己打开</td>
 *       <td>{@code settings put secure location_mode 3} 直接开</td></tr>
 * </table>
 *
 * <p>Android 13（API 33）起 {@code BluetoothAdapter.enable()} 对第三方 App
 * 直接无效（只对系统 App 生效），所以蓝牙的兜底必须是弹对话框，
 * 不能指望代码调用。
 *
 * <p>所有方法都**不会抛异常**，失败返回 false —— 调用方按"没打开"处理，
 * 退回到文字提示。
 */
public final class SystemToggles {

    private static final String TAG = "HuChuan.Toggles";

    /** 定位的高精度模式值（{@code Settings.Secure.LOCATION_MODE_HIGH_ACCURACY}）。 */
    private static final int LOCATION_MODE_HIGH_ACCURACY = 3;

    /** su 候选路径，与 {@link RootMacReader} 保持一致。 */
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
    };

    /** 找到的 su 路径；null 表示没试过或没找到。 */
    private static volatile String suPath;
    private static volatile boolean suProbed;

    private SystemToggles() {}

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    /** 蓝牙是否已开启。 */
    public static boolean isBluetoothOn(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager)
                    ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter a = bm.getAdapter();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "查蓝牙状态失败: " + t);
            return false;
        }
    }

    /** 定位服务是否已开启（跟"有没有定位权限"是两件事）。 */
    public static boolean isLocationOn(Context ctx) {
        return Perms.isLocationEnabled(ctx);
    }

    /** 两个开关是不是都开着（都开着就不必做任何事）。 */
    public static boolean allReady(Context ctx) {
        return isBluetoothOn(ctx) && isLocationOn(ctx);
    }

    // ------------------------------------------------------------------
    // 自动开启（root）
    // ------------------------------------------------------------------

    /**
     * 尝试用 root 直接打开蓝牙，并等它真的变成 ON。
     *
     * <p>为什么要等：{@code svc bluetooth enable} 只是发出请求，
     * 适配器从 OFF 到 ON 要 1~3 秒（栈要重新初始化）。发完命令立刻
     * 开始扫描会扫不到任何东西 —— 这也是"看起来没生效"的常见原因。
     *
     * @return 最终是否处于开启状态
     */
    public static boolean tryEnableBluetoothByRoot(Context ctx) {
        if (isBluetoothOn(ctx)) return true;
        if (!runSu("svc bluetooth enable", 4000)) return false;
        return waitFor(new Condition() {
            @Override public boolean ok() { return isBluetoothOn(ctx); }
        }, 6000);
    }

    /**
     * 尝试用 root 直接打开定位。
     *
     * <p>写的是 {@code settings put secure location_mode 3}（高精度）。
     * 之所以不写 2（省电）或 1（仅设备）：BLE 扫描要的是"定位服务活着"，
     * 高精度最稳，且和用户自己在设置里打开的效果一致。
     *
     * @return 最终是否处于开启状态
     */
    public static boolean tryEnableLocationByRoot(Context ctx) {
        if (isLocationOn(ctx)) return true;
        if (!runSu("settings put secure location_mode "
                + LOCATION_MODE_HIGH_ACCURACY, 4000)) {
            return false;
        }
        return waitFor(new Condition() {
            @Override public boolean ok() { return isLocationOn(ctx); }
        }, 4000);
    }

    /** 是否拿到了 root（探测一次后缓存结果）。 */
    public static boolean hasRoot() {
        return findSu() != null;
    }

    // ------------------------------------------------------------------
    // 兜底：跳系统界面（无 root 时唯一合规路径）
    // ------------------------------------------------------------------

    /**
     * 弹出系统的"开启蓝牙"对话框。
     *
     * <p>这是 Android 13+ 第三方 App 唯一能做的 —— {@code BluetoothAdapter.enable()}
     * 对普通 App 已经无效，调了也只是静默返回 false。
     */
    public static void requestBluetoothViaDialog(Activity a) {
        try {
            a.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 0x5B1);
        } catch (Throwable t) {
            Log.w(TAG, "弹蓝牙对话框失败: " + t);
            openBluetoothSettings(a);
        }
    }

    /** 跳到系统蓝牙设置页。 */
    public static void openBluetoothSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Throwable t) {
            Log.w(TAG, "跳蓝牙设置失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private interface Condition { boolean ok(); }

    /** 轮询等待某个条件成立。 */
    private static boolean waitFor(Condition c, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.ok()) return true;
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return c.ok();
    }

    /** 找一个能用的 su；找不到返回 null。 */
    private static String findSu() {
        if (suProbed) return suPath;
        synchronized (SystemToggles.class) {
            if (suProbed) return suPath;
            for (String cand : SU_CANDIDATES) {
                if (canExec(cand)) {
                    suPath = cand;
                    Log.i(TAG, "找到 su: " + cand);
                    break;
                }
            }
            if (suPath == null) Log.i(TAG, "没有可用的 su，只能走系统对话框");
            suProbed = true;
            return suPath;
        }
    }

    /** 这个路径的 su 能不能真的执行（有的机器有文件但没授权）。 */
    private static boolean canExec(String su) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{su, "-c", "id"});
            drain(p);
            return waitExit(p, 2500) == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    /** 用 su 跑一条命令，返回是否成功（退出码 0）。 */
    private static boolean runSu(String cmd, long timeoutMs) {
        String su = findSu();
        if (su == null) return false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
            drain(p);
            int code = waitExit(p, timeoutMs);
            if (code != 0) Log.w(TAG, "su 命令失败(" + code + "): " + cmd);
            return code == 0;
        } catch (Throwable t) {
            Log.w(TAG, "su 执行异常: " + cmd + " → " + t);
            return false;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * 把 stdout / stderr 读干。
     *
     * <p>★必须做★：su 的输出如果没人读，缓冲区写满后子进程会**永久阻塞**，
     * 表现为"命令发出去了但一直不返回"。这是踩过的坑。
     */
    private static void drain(final Process p) {
        Thread t1 = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    while (r.readLine() != null) { /* 丢弃 */ }
                } catch (Throwable ignored) { }
            }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()));
                    while (r.readLine() != null) { /* 丢弃 */ }
                } catch (Throwable ignored) { }
            }
        });
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
    }

    /** 等进程退出，返回退出码；超时返回 -1。 */
    private static int waitExit(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return -1;
    }
}
