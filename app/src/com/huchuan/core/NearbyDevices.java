package com.huchuan.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 附近设备的**全局缓存** —— 供设置页「附近设备」列表显示。
 *
 * <h3>为什么需要这个类（而不是直接用 GattClient 的内部表）</h3>
 *
 * {@code GattClient} 是**一次性**的扫描器：分享界面 new 一个、扫完丢掉，
 * 它的 {@code names} / {@code advOf} / {@code found} 三张表都是 private 实例字段。
 * 设置页只是**旁观**（不发起分享），拿不到那些表，也不该为了显示列表
 * 而自己去开一路扫描（会跟分享页抢扫描器、白耗电）。
 *
 * <p>所以这里做一个进程级的静态缓存：<b>谁在扫，就往这里写一笔记一笔</b>；
 * 设置页只读。没有扫描进行时列表自然为空（并显示提示文案）。
 *
 * <h3>过期策略</h3>
 *
 * BLE 广播只要设备还在附近就会持续到达，所以「多久没再收到 = 不在了」。
 * 超过 {@link #STALE_MS} 未更新即视为离开 —— 与界面刷新周期（1s）配合，
 * 用户看到的是「走进来就出现、走开就消失」。
 *
 * <h3>线程安全</h3>
 *
 * 写入来自 BLE 扫描回调线程，读取来自 UI 主线程。所有方法都 synchronize 在同一把锁上，
 * 且 {@link #snapshot()} 返回**副本**，调用方遍历时不会被并发修改干扰。
 */
public final class NearbyDevices {

    /** 多久没收到广播就认为设备已离开（毫秒）。 */
    public static final long STALE_MS = 15_000L;

    /** 至少见到过一次才算数：RSSI 太弱的广播抖动大，先不显示。 */
    public static final int MIN_RSSI = -95;

    private static final Object LOCK = new Object();

    /** key = 大写 MAC → 记录。 */
    private static final java.util.Map<String, Device> DEVICES =
            new java.util.HashMap<String, Device>();

    private NearbyDevices() { }

    /** 一台被发现的设备。 */
    public static final class Device {
        /** 蓝牙地址（大写）。 */
        public final String address;
        /** 设备名；可能为 null（对方没广播名字）。 */
        public final String name;
        /** 最近一次 RSSI（dBm，负数，越接近 0 越强）。 */
        public final int rssi;
        /** 首次发现时间（SystemClock 语义的毫秒）。 */
        public final long firstSeenMs;
        /** 最近一次收到广播的时间。 */
        public final long lastSeenMs;
        /** 厂商代码（小米=0x2839 等），未知为 -1。 */
        public final int manufactureCode;
        /** 设备类别代码，未知为 -1。 */
        public final int deviceCode;

        Device(String address, String name, int rssi,
               long firstSeenMs, long lastSeenMs, int manufactureCode, int deviceCode) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
            this.firstSeenMs = firstSeenMs;
            this.lastSeenMs = lastSeenMs;
            this.manufactureCode = manufactureCode;
            this.deviceCode = deviceCode;
        }
    }

    /**
     * 记录一次发现（由 {@code GattClient} 的扫描回调调用）。
     *
     * <p>同名同地址的设备会被合并、只保留最新 RSSI 与时间。
     */
    @SuppressWarnings("MissingPermission")
    public static void onDiscovered(android.bluetooth.BluetoothDevice device,
                                    int rssi, String name,
                                    GattClient.AdvInfo adv) {
        if (device == null) return;
        if (rssi < MIN_RSSI) return;

        String addr;
        try {
            addr = device.getAddress();
        } catch (Throwable t) {
            return;   // 权限被撤时 getAddress 会抛
        }
        if (addr == null || addr.isEmpty()) return;
        addr = addr.toUpperCase(Locale.ROOT);

        // 名字可能这次没有、上次有 —— 不要用 null 把已有名字冲掉
        String cleanName = (name != null && !name.trim().isEmpty()) ? name.trim() : null;
        int mfg = adv != null ? adv.manufactureCode : -1;
        int code = adv != null ? adv.deviceCode : -1;

        synchronized (LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            Device old = DEVICES.get(addr);
            if (old == null) {
                DEVICES.put(addr, new Device(addr, cleanName, rssi, now, now, mfg, code));
            } else {
                DEVICES.put(addr, new Device(
                        addr,
                        cleanName != null ? cleanName : old.name,
                        rssi,
                        old.firstSeenMs,
                        now,
                        mfg >= 0 ? mfg : old.manufactureCode,
                        code >= 0 ? code : old.deviceCode));
            }
        }
    }

    /**
     * 取当前仍在线（{@link #STALE_MS} 内有过广播）的设备，**按信号强度降序**。
     *
     * @return 副本列表；永远不为 null
     */
    public static List<Device> snapshot() {
        long now = android.os.SystemClock.elapsedRealtime();
        List<Device> out = new ArrayList<Device>();
        synchronized (LOCK) {
            java.util.Iterator<java.util.Map.Entry<String, Device>> it =
                    DEVICES.entrySet().iterator();
            while (it.hasNext()) {
                Device d = it.next().getValue();
                if (now - d.lastSeenMs > STALE_MS) {
                    it.remove();          // 顺手清理，避免表无限增长
                } else {
                    out.add(d);
                }
            }
        }
        // 信号强的排前面（rssi 是负数，越大越强）
        Collections.sort(out, new Comparator<Device>() {
            @Override public int compare(Device a, Device b) {
                return b.rssi - a.rssi;
            }
        });
        return out;
    }

    /** 清空缓存（停止扫描、页面销毁时调用，避免显示过期设备）。 */
    public static void clear() {
        synchronized (LOCK) {
            DEVICES.clear();
        }
    }

    /** 当前在线设备数。 */
    public static int onlineCount() {
        return snapshot().size();
    }

    /**
     * RSSI → 信号格数（1..4），照常见做法分档。
     *
     * <p>-50dBm 以上满格，-90 以下一格。给界面画信号强度用。
     */
    public static int signalBars(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -70) return 3;
        if (rssi >= -82) return 2;
        return 1;
    }
}
