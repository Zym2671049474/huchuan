package com.huchuan.core;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** 运行时权限。互传需要蓝牙(发现/广播/连接) + 位置(部分 ROM 的 P2P 扫描依赖)。 */
public final class Perms {

    /** 按 API 版本组装所需权限。 */
    public static String[] required() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        // P2P 扫描在多数 ROM 上仍要求定位
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return p.toArray(new String[0]);
    }

    /** 尚未授予的权限。 */
    public static String[] missing(Activity a) {
        List<String> out = new ArrayList<>();
        for (String p : required()) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    public static boolean allGranted(Activity a) {
        return missing(a).length == 0;
    }

    /**
     * 系统**定位服务**是否开启（注意：这跟"有没有定位权限"是两回事）。
     *
     * <h3>为什么必须单独检查这个（P133）</h3>
     *
     * Android 的 BLE 扫描**强制依赖系统定位服务**：BLE 广播可被用来推断位置，
     * 所以系统把"能扫广播"和"定位开着"绑在一起。定位关闭时：
     *
     * <ul>
     *   <li>权限明明都授予了（{@link #allGranted} 返回 true）</li>
     *   <li>{@code startScan()} **不报错**，回调也正常走 onScanResult 注册</li>
     *   <li>但**一个广播都收不到** —— 静默失败，界面上只是"一直在搜索…"</li>
     * </ul>
     *
     * 实测踩到：定位关着时分享卡片和设置页都扫不到任何设备，
     * 日志只有"开始扫描"没有后续。打开定位立刻恢复。
     *
     * <p>判断方式用 {@link LocationManager#isLocationEnabled()}（API 28+），
     * 老版本回落到读 LOCATION_PROVIDERS_ALLOWED 设置项。
     */
    public static boolean isLocationEnabled(android.content.Context ctx) {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) ctx.getSystemService(
                            android.content.Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) {
                return lm.isLocationEnabled();
            }
            // API 28 以下没有 isLocationEnabled()，读老设置项
            int mode = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF);
            return mode != android.provider.Settings.Secure.LOCATION_MODE_OFF;
        } catch (Throwable t) {
            // 读不到就当"没开" —— 宁可多提示一句，也别让用户对着空列表干等
            return false;
        }
    }


    private Perms() {}
}
