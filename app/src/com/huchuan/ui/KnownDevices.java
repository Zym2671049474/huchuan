package com.huchuan.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 记住上次扫描到的设备。
 *
 * 为什么需要：从系统「分享」进入时，BLE 扫描要 2–4 秒才出结果，
 * 而弹窗是立刻显示的 —— 用户第一眼看到的是"没有设备"。
 * 有了缓存，上次见到的设备（比如常连的小米15）**立刻**就能列出来，
 * 后台扫描再刷新，观感上就是"秒出"。
 *
 * 缓存的设备只是"用来立刻显示"，真正发送前仍会等扫描确认它还在
 * （见 SenderService 的 targetAddr 逻辑），所以不会发到已离开的设备。
 */
public final class KnownDevices {

    private static final String PREF = "huchuan_known_devices";
    private static final String KEY = "devices";
    /** 超过这个时间没再见到，就不再"秒出"（避免列表里全是旧设备）。 */
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** 一台已知设备。 */
    public static final class Entry {
        public final String address;
        public final String name;
        public final long seenAt;
        public Entry(String address, String name, long seenAt) {
            this.address = address; this.name = name; this.seenAt = seenAt;
        }
    }

    private KnownDevices() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 上次见到的设备，按最近见到排序。 */
    public static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        String raw = prefs(c).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        long now = System.currentTimeMillis();
        // 格式：addr\u0001name\u0001ts\u0002addr\u0001name\u0001ts…
        for (String rec : raw.split("\u0002")) {
            if (rec.isEmpty()) continue;
            String[] f = rec.split("\u0001", -1);
            if (f.length < 3) continue;
            try {
                long ts = Long.parseLong(f[2]);
                if (now - ts > TTL_MS) continue;
                out.add(new Entry(f[0], f[1], ts));
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 记下一台设备（存在则更新时间/名字）。 */
    public static void remember(Context c, String address, String name) {
        if (address == null || address.isEmpty()) return;
        List<Entry> list = load(c);
        String n = name == null ? "" : name.trim();
        List<Entry> next = new ArrayList<Entry>();
        next.add(new Entry(address, n, System.currentTimeMillis()));
        for (Entry e : list) {
            // 去重必须**同时**看地址和名字：
            //   · 同地址  → 同一台，更新即可
            //   · 同名字  → 也是同一台（BLE 随机地址每次都变！）
            // 只按地址去重会让"Mi 15"在缓存里堆成好几条，
            // 下次打开卡片就会重复显示（实测出现过 3 条）。
            if (e.address.equalsIgnoreCase(address)) continue;
            if (n.isEmpty()) continue;
            if (n.equalsIgnoreCase(e.name == null ? "" : e.name.trim())) continue;
            next.add(e);
        }
        // 只留最近 12 台，prefs 别无限长
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < next.size() && i < 12; i++) {
            Entry e = next.get(i);
            if (i > 0) sb.append('\u0002');
            sb.append(e.address).append('\u0001').append(e.name).append('\u0001').append(e.seenAt);
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }
}
