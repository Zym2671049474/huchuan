package com.huchuan.ui;

import java.util.Locale;

/**
 * 按设备名推断设备类型，用于选择头像。
 *
 * 互传协议的 DeviceInfo 里没有设备类型字段，设备名靠 BLE 广播的
 * scan response 传过来（16 字节），所以只能按名字猜。
 */
public final class DeviceKind {

    public enum Kind { PHONE, LAPTOP, TABLET, UNKNOWN }

    /** 笔记本/电脑常见关键词。 */
    private static final String[] LAPTOP = {
            "laptop", "notebook", "macbook", "thinkpad", "ideapad", "thinkbook",
            "book", "pc", "desktop", "computer", "workstation", "surface",
            "xps", "vivobook", "zenbook", "matebook", "redmibook", "magicbook",
            "legion", "rog", "predator", "aspire", "pavilion", "elitebook",
            "probook", "latitude", "precision", "yoga", "inspiron", "vaio",
            "chromebook", "imac", "mac mini", "mac pro", "mac studio",
    };

    /** 平板关键词。 */
    private static final String[] TABLET = {
            "ipad", "pad", "tab", "tablet", "matepad", "mi pad", "surface pro",
    };

    /** 手机品牌/系列关键词。 */
    private static final String[] PHONE = {
            "phone", "mi ", "redmi", "xiaomi", "huawei", "honor", "oppo", "vivo",
            "oneplus", "realme", "meizu", "smartisan", "nokia", "sony", "xperia",
            "pixel", "nexus", "galaxy", "samsung", "iphone", "motorola", "moto ",
            "zte", "lenovo", "asus", "rog phone", "blackshark", "nubia",
    };

    private DeviceKind() {}

    /**
     * ★按小米互传的 deviceCode 判定类型★（P104）
     *
     * 这个表是从小米互传 4.12.0 里挖出来的
     * （`com.miui.mishare.app.view.f.setDeviceStyle` 的 packed-switch），
     * 比按名字猜关键词**准确得多** —— 名字是用户随便改的，code 是协议定的。
     *
     *   0  → 隐藏（不显示设备）
     *   1  → 手机      10 → 手机
     *   2  → 电脑      12,13,14,15,16 → 电脑
     *   3  → 平板      11 → 平板
     *   4  → iPhone    5  → iPad
     *   6  → Mac       17,18,19,20 → Mac
     *   7  → pad(global)   8 → pc(global)   9 → phone(global)
     *  其它 → 手机（小米互传的默认分支就是手机）
     *
     * 实测小米15 = code 30（超出表范围）→ 落到默认 → 手机 ✅
     */
    public static Kind fromCode(int code) {
        switch (code) {
            case 0:  return Kind.UNKNOWN;   // 隐藏
            case 3:
            case 11: return Kind.TABLET;
            case 2:
            case 8:
            case 12: case 13: case 14: case 15: case 16:
                     return Kind.LAPTOP;
            default: return Kind.PHONE;     // 含 1/7/9/10 及一切未知 code
        }
    }

    /**
     * 判定设备类型：**优先用协议里的 deviceCode**，拿不到才按名字猜。
     */
    public static Kind of(String name, int deviceCode) {
        if (deviceCode > 0) return fromCode(deviceCode);
        return of(name);
    }

    public static Kind of(String name) {
        if (name == null) return Kind.UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) return Kind.UNKNOWN;

        // 平板优先于手机判断（"mi pad" 既是 mi 也是 pad）
        if (containsAny(n, TABLET)) return Kind.TABLET;
        if (containsAny(n, LAPTOP)) return Kind.LAPTOP;
        if (containsAny(n, PHONE)) return Kind.PHONE;
        return Kind.UNKNOWN;
    }

    private static boolean containsAny(String n, String[] keys) {
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /** 头像资源名对应的 drawable id 由调用方用 getIdentifier 取，这里给后缀。 */
    public static String iconSuffix(Kind k) {
        switch (k) {
            case PHONE:  return "phone";
            case LAPTOP: return "laptop";
            case TABLET: return "tablet";
            default:     return "unknown";
        }
    }
}
