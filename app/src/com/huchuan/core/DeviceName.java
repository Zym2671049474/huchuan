package com.huchuan.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 读取「设备名称」—— 设置 → 关于手机 → 设备名称。
 *
 * 对外广播的名字要和用户在系统里设的一致，
 * 而不是 Build.MODEL（那是代号，比如 "2201122C"，对方看不懂）。
 *
 * 取值顺序照抄 CatShare 的 AppSettings.systemDeviceName：
 *   1. Settings.Global.device_name      ← 「设备名称」就是这一项
 *   2. Settings.Secure.bluetooth_name   ← 蓝牙名，通常同步
 *   3. Build.MODEL                      ← 兜底
 */
public final class DeviceName {

    private DeviceName() {}

    /** 默认值：三项都拿不到时用。 */
    private static final String FALLBACK = "Android";

    public static String get(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String n = Settings.Global.getString(cr, "device_name");
            if (n != null && !n.trim().isEmpty()) return n.trim();

            n = Settings.Secure.getString(cr, "bluetooth_name");
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable t) {
            // 读设置失败（权限/异常）→ 走兜底，不要因此让整个发送/接收挂掉
            android.util.Log.w("HuChuan.DeviceName", "读取设备名称失败: " + t);
        }
        String m = Build.MODEL;
        return (m == null || m.trim().isEmpty()) ? FALLBACK : m.trim();
    }
}
