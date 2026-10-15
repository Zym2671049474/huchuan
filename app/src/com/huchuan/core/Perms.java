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


    private Perms() {}
}
