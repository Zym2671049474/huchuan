package com.huchuan.core;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.huchuan.model.DeviceInfo;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 发送方的 BLE 扫描 + GATT 客户端。
 *
 * 流程：
 *   1. 扫描 ADV_SERVICE_UUID
 *   2. 扫到设备后 connectGatt
 *   3. discoverServices → 读 CHAR_STATUS 拿 DeviceInfo（含公钥）
 *   4. 用公钥算 cipher → 加密 P2pInfo → 写 CHAR_P2P
 *
 * ⚠️ 读 CHAR_STATUS 也要处理分块：onCharacteristicRead 回来可能只是第一段，
 *    需要按 offset 反复 readCharacteristic（见 P018）。
 */
public final class GattClient {

    private static final String TAG = "HuChuan.GattClient";

    public interface Listener {
        /**
         * ★带名字的 onFound★（P100）—— 唯一的设备回调。
         *
         * 为什么不让调用方用 getNameOf(addr) 反查：
         * 扫描回调是在 `new GattClient(...)` **还没返回**时就开始触发的，
         * 此时调用方的 client 字段还是 null → 反查拿不到
         * → 回落到 device.getName()（BLE 随机地址下为 null）
         * → 界面显示成 MAC 地址"未识别的设备"。
         * 直接把名字随回调传出去，绕开这个时序问题。
         */
        void onFound(BluetoothDevice device, int rssi, String name);

        void onDeviceInfo(BluetoothDevice device, DeviceInfo info);
        void onState(String msg);
    }

    private final Context ctx;
    private final Listener listener;
    private final BluetoothManager btManager;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private volatile BluetoothDevice target;
    private volatile BluetoothDevice lastFound;
    private volatile boolean scanning;
    /** 本次扫描期间发现的全部设备：地址 -> 设备。保持发现顺序。 */
    private final java.util.Map<String, BluetoothDevice> found =
            new java.util.LinkedHashMap<String, BluetoothDevice>();

    private final CountDownLatch infoLatch = new CountDownLatch(1);
    private final AtomicReference<DeviceInfo> deviceInfo = new AtomicReference<>();

    /** 地址 → 从广播里解出的设备名（没有则为 null）。 */
    private final java.util.Map<String, String> names =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * 从广播的 service data 里解出设备名。
     *
     * ★这是 CatShare 的做法★（反编译 ShareActivityKt$deviceScanner$1$1$callback$1）：
     *   serviceData 长度 == 27 (0x1b) 时：
     *       name = ASCII( serviceData[10 .. 25] )   // 遇 0x00 截断，去掉尾部 "\t"
     *       id   = "%04x" % ((serviceData[8] << 8) | serviceData[9])
     *   长度 == 6 时只有 MAC，没有名字。
     *
     * BLE 广播里**没有标准设备名**（没有 0x09 Complete Local Name），
     * 名字就藏在这段自定义 service data 里 —— 这就是之前只能显示 MAC 的原因。
     *
     * @return 设备名，解析不出来返回 null
     */
    public static String parseNameFromScanRecord(android.bluetooth.le.ScanRecord rec) {
        if (rec == null) return null;
        try {
            java.util.Map<android.os.ParcelUuid, byte[]> data = rec.getServiceData();
            if (data == null) return null;
            for (byte[] v : data.values()) {
                if (v == null || v.length != 0x1b) continue;     // 27 字节才是带名字的格式
                StringBuilder sb = new StringBuilder();
                for (int i = 0x0a; i < 0x1a && i < v.length; i++) {
                    if (v[i] == 0) break;                        // 遇 0 结束
                    sb.append((char) (v[i] & 0xFF));
                }
                String name = sb.toString();
                // 去掉尾部制表符（CatShare 也是这么处理的）
                while (name.endsWith("\t")) name = name.substring(0, name.length() - 1);
                name = name.trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析广播里的设备名失败: " + t);
        }
        return null;
    }

    /**
     * 每台设备 dump **一次**广播内容（按地址去重），避免刷屏（P100）。
     *
     * 原来用全局 boolean 只 dump 第一台 —— 结果 dump 到的往往不是目标设备
     * （实测 dump 了 6F:33:… 而目标是小 52:B7:…），关键信息被漏掉。
     */
    private final java.util.Set<String> dumpedAddrs =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private void dumpScanRecordOnce(BluetoothDevice d, ScanResult result) {
        if (!dumpedAddrs.add(d.getAddress())) return;
        try {
            android.bluetooth.le.ScanRecord rec = result.getScanRecord();
            Log.i(TAG, "==== 广播原文 dump（" + d.getAddress() + "）====");
            if (rec == null) { Log.i(TAG, "  scanRecord = null"); return; }
            java.util.Map<android.os.ParcelUuid, byte[]> sd = rec.getServiceData();
            if (sd == null || sd.isEmpty()) {
                Log.i(TAG, "  serviceData 为空");
            } else {
                for (java.util.Map.Entry<android.os.ParcelUuid, byte[]> e : sd.entrySet()) {
                    byte[] v = e.getValue();
                    StringBuilder hex = new StringBuilder();
                    StringBuilder asc = new StringBuilder();
                    for (int i = 0; v != null && i < v.length; i++) {
                        hex.append(String.format(java.util.Locale.ROOT, "%02x ", v[i]));
                        asc.append(v[i] >= 0x20 && v[i] < 0x7f ? (char) v[i] : '.');
                    }
                    Log.i(TAG, "  serviceData uuid=" + e.getKey().getUuid()
                            + " len=" + (v == null ? -1 : v.length));
                    Log.i(TAG, "    hex: " + hex);
                    Log.i(TAG, "    asc: " + asc);
                }
            }
            // 标准 Complete/Short Local Name
            java.util.List<android.os.ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                StringBuilder sb = new StringBuilder();
                for (android.os.ParcelUuid u : uuids) sb.append(u.getUuid()).append(' ');
                Log.i(TAG, "  serviceUuids: " + sb);
            }
            byte[] raw = rec.getBytes();
            if (raw != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : raw) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
                Log.i(TAG, "  原始广播: " + hex);
            }
            String devName = null;
            try { devName = d.getName(); } catch (Throwable ignored) {}
            Log.i(TAG, "  BluetoothDevice.getName() = " + devName);
            Log.i(TAG, "==== dump 结束 ====");
        } catch (Throwable t) {
            Log.w(TAG, "dump 广播失败: " + t);
        }
    }

    /**
     * ★按小米互传的布局解析**整包原始广播**★（P104）
     *
     * 这是从 `com.miui.mishare.connectivity.ble.central.MiBleDevice.fromScanResult`
     * 反编译出来的真实布局（小米互传 4.12.0）。它读的是
     * `ScanRecord.getBytes()` —— **整个广播包的原始字节**，
     * 不是我们之前用的 `getServiceData()`！
     *
     * 偏移（相对整包起点）：
     *   0x17        deviceCode      设备类别（手机/电脑/平板…）
     *   0x18        flags 位域      bit0=5GHz bit1=turbo bit2=LyraScan
     *                              bit3=LyraNet bit4=turbo2
     *   0x19..0x1e  deviceIdHigh   6 字节
     *   0x21,0x22   manufactureCode 厂商代码
     *   0x23..0x2c  deviceIdLow    10 字节
     *   0x2d..0x3c  nickName       16 字节，设备名
     *   0x3d        version        协议版本
     *
     * 名字的读法（照抄它）：从 16 字节里**从后往前**跳过 0，
     * 遇到 0x09('\t') 说明被截短了（nickNameHasMore），
     * 然后取前 N 字节按 UTF-8 解。
     *
     * @return 解析结果；广播长度不够或异常返回 null
     */
    public static final class AdvInfo {
        public String name;
        public int deviceCode = -1;
        public int manufactureCode = -1;
        public int version = -1;
        public boolean support5g;
        public boolean nameHasMore;
        @Override public String toString() {
            return "name=" + name + " code=" + deviceCode
                    + " mfr=" + manufactureCode + " ver=" + version
                    + " 5g=" + support5g + " more=" + nameHasMore;
        }
    }

    public static AdvInfo parseAdv(android.bluetooth.le.ScanRecord rec) {
        if (rec == null) return null;
        byte[] b = null;
        try { b = rec.getBytes(); } catch (Throwable ignored) {}
        if (b == null) return null;
        // 名字读的是 0x2d..0x3c，包至少要 0x3e 字节
        if (b.length < 0x3e) return null;
        try {
            AdvInfo a = new AdvInfo();
            a.deviceCode = b[0x17] & 0xff;
            a.manufactureCode = ((b[0x21] & 0xff) << 8) | (b[0x22] & 0xff);
            a.version = b[0x3d] & 0xff;
            byte f = b[0x18];
            a.support5g = (f & 0x01) != 0;

            // ★名字：抄 MiBleDevice 的扫描方式★
            //   取 0x2d 起 16 字节，从**下标 15 往前**：
            //     遇到 '	'(9) → 标记截断，停
            //     遇到非 0    → 下标 +1（即长度）后停
            //     遇到 0      → 下标 -1 继续往前
            byte[] n = new byte[16];
            System.arraycopy(b, 0x2d, n, 0, 16);
            int idx = 0xf;
            while (idx >= 0) {
                byte c = n[idx];
                if (c == 0x09) { a.nameHasMore = true; break; }
                if (c != 0) { idx++; break; }
                idx--;
            }
            if (idx > 0) {
                a.name = new String(n, 0, Math.min(idx, 16),
                        java.nio.charset.StandardCharsets.UTF_8);
            } else {
                a.name = "";
            }
            return a;
        } catch (Throwable t) {
            Log.w(TAG, "解析整包广播失败: " + t);
            return null;
        }
    }

    /** 取已解析出的设备名（没有则 null）。 */
    public String getNameOf(String address) {
        return address == null ? null : names.get(address.toUpperCase(java.util.Locale.ROOT));
    }

    public GattClient(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
        this.btManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    // ---- 扫描 ----

    @SuppressWarnings("MissingPermission")
    public void startScan() throws Exception {
        if (btManager == null || btManager.getAdapter() == null) throw new IllegalStateException("无蓝牙适配器");
        if (!btManager.getAdapter().isEnabled()) throw new IllegalStateException("蓝牙未开启");
        scanner = btManager.getAdapter().getBluetoothLeScanner();
        if (scanner == null) throw new IllegalStateException("无法获取 BLE 扫描器");

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleUtils.ADV_SERVICE_UUID))
                .build();
        // ★两档扫描模式★（P113，学小米互传）
        //
        // 反编译 `com/miui/mishare/connectivity/ble/central/a.smali`：
        //   const/4 v3, 0x2  → setScanMode(2)  SCAN_MODE_LOW_LATENCY
        //   const/4 v2, 0x0  → setScanMode(0)  SCAN_MODE_LOW_POWER
        // 它在**两个档位之间切换** —— 活跃时高灵敏，后台时省电。
        //
        // 我们照做：卡片在前台 → LOW_LATENCY（不漏包）；
        // 卡片被切到后台 → LOW_POWER（省电，且此时用户也看不到列表）。
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(scanMode)
                .build();

        scanning = true;
        found.clear();
        lastFound = null;
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.i(TAG, "开始扫描 ADV_SERVICE_UUID，模式="
                + (scanMode == ScanSettings.SCAN_MODE_LOW_LATENCY ? "LOW_LATENCY" : "LOW_POWER"));
        state("扫描中…");
    }

    /**
     * 当前扫描档位。
     *
     * ★改这个值必须重启扫描才生效★ —— ScanSettings 是 startScan 的参数，
     * 系统不接受"运行时调档"。所以 {@link #setScanMode(int)} 内部要
     * stop + start。
     */
    private int scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;

    /**
     * 切换扫描档位（幂等；值没变就什么都不做）。
     *
     * @param mode {@link ScanSettings#SCAN_MODE_LOW_LATENCY} 或
     *             {@link ScanSettings#SCAN_MODE_LOW_POWER}
     */
    @SuppressWarnings("MissingPermission")
    public void setScanMode(int mode) {
        if (mode == scanMode) return;
        Log.i(TAG, "切换扫描模式 " + scanMode + " → " + mode);
        scanMode = mode;
        if (!scanning) return;          // 没在扫，下次 startScan 自然用新值
        try {
            // 重启扫描：停 → 立刻起（不置 scanning=false，避免并发问题）
            if (scanner != null) scanner.stopScan(scanCallback);
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleUtils.ADV_SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (Throwable t) {
            Log.w(TAG, "切换扫描模式失败", t);
        }
    }

    @SuppressWarnings("MissingPermission")
    public void stopScan() {
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            // 名字藏在 service data 里，不在标准设备名里（见 parseNameFromScanRecord）
            // ★整包解析（小米互传的布局）优先★（P104）
            //
            // 之前只用 getServiceData() 读 27 字节，那个偏移是"serviceData 内"的；
            // 而小米互传读的是**整包** getBytes() 的 0x2d。
            // 两者不可混用。整包解析还顺带给出 deviceCode（设备类别）。
            AdvInfo adv = parseAdv(result.getScanRecord());
            if (adv != null) {
                String k2 = d.getAddress().toUpperCase(java.util.Locale.ROOT);
                advOf.put(k2, adv);
                if (adv.name != null && !adv.name.isEmpty()) {
                    String oldN = names.put(k2, adv.name);
                    if (!adv.name.equals(oldN)) {
                        Log.i(TAG, "整包解析: " + d.getAddress() + " 名字=" + adv.name
                                + " code=" + adv.deviceCode + " ver=" + adv.version);
                    }
                }
            }
            String name = (adv != null && adv.name != null && !adv.name.isEmpty())
                    ? adv.name : parseNameFromScanRecord(result.getScanRecord());
            if (name != null) {
                String key = d.getAddress().toUpperCase(java.util.Locale.ROOT);
                String old = names.put(key, name);
                if (!name.equals(old)) Log.i(TAG, "发现设备: " + d.getAddress() + " 名字=" + name);
            }
            // ★无条件 dump 第一条广播★（P100）
            //
            // 原来只在解析失败时 dump。但"识别不出设备名"有两种可能：
            //   a. 解析失败（name == null）
            //   b. 解析成功但**没被用上**（比如长度不是 27）
            // 只在失败时 dump 会漏掉 b。所以第一条一律打出来。
            dumpScanRecordOnce(d, result);
            Log.i(TAG, "发现设备: " + d.getAddress() + " rssi=" + result.getRssi()
                    + (name != null ? " name=" + name : ""));
            lastFound = d;
            found.put(d.getAddress(), d);
            // ★记进全局缓存（P132）★ 供设置页「附近设备」列表显示。
            // 放在这里而不是让设置页自己扫：设置页不该为了显示列表再占一路
            // 扫描器（会跟分享页抢，且白耗电）。扫描器反正一直在跑，顺手记一笔。
            NearbyDevices.onDiscovered(d, result.getRssi(), name, adv);
            // 把解析出的名字**直接传出去**（P100）—— 调用方不必反查
            if (listener != null) listener.onFound(d, result.getRssi(), name);
        }
        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败: " + errorCode);
            state("扫描失败: code " + errorCode);
        }
    };

    /** 地址（大写）→ 整包解析结果（含 deviceCode）。 */
    private final java.util.Map<String, AdvInfo> advOf =
            new java.util.concurrent.ConcurrentHashMap<String, AdvInfo>();

    /** 取整包解析结果（含设备类别），没有则 null。 */
    public AdvInfo getAdvInfo(String address) {
        return address == null ? null
                : advOf.get(address.toUpperCase(java.util.Locale.ROOT));
    }

    /** 最近一次扫描到的设备（供上层轮询）。 */
    public BluetoothDevice getLastFound() { return lastFound; }

    /**
     * 按地址取已发现的设备。
     *
     * 必须保留全部设备：只记 lastFound 时，若目标之后又出现别的设备，
     * 目标就被覆盖了 —— 表现为"选中的设备发不出去"。
     */
    public BluetoothDevice getFound(String address) {
        if (address == null) return null;
        for (BluetoothDevice d : found.values()) {
            if (address.equalsIgnoreCase(d.getAddress())) return d;
        }
        // ★地址没匹配上时，退化为"按名字找"★（P083 / P099）
        //
        // 小米15 的 BLE 地址是**每次扫描都随机**的
        // （实测 64:FD:E1:… / 76:C9:91:… / 6D:B0:19:… 全不一样），
        // 而分享卡片上缓存的地址是上一次扫描的，**永远匹配不上**。
        // 于是第一次发送必然等满 20 秒后失败，第二次才成功 ——
        // 因为第二次列表里已经是新扫到的地址了。
        //
        // 名字来自广播 service data，是稳定的，用它兜底。
        return findByName(wantedNames.get(address));
    }

    /**
     * 按名字在**已扫到的**设备里找。
     *
     * 单独抽出来，因为有两个入口需要它：
     *   · getFound(过期地址)     → 名字兜底
     *   · getFoundByName(名字)   → 名字直查（P099 主路径）
     */
    public BluetoothDevice findByName(String want) {
        if (want == null || want.trim().isEmpty()) return null;
        final String w = want.trim();
        for (BluetoothDevice d : found.values()) {
            String n = nameOf(d.getAddress());
            if (n != null && w.equalsIgnoreCase(n.trim())) return d;
            // 广播里没有名字时，退一步用系统缓存的设备名比对
            try {
                String sys = d.getName();
                if (sys != null && w.equalsIgnoreCase(sys.trim())) return d;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * ★按名字直查★（P099）—— 拿不到可用地址时的**主路径**。
     *
     * 分享卡片上那个地址可能已经过期（对方换了随机 MAC），
     * 这时"地址优先"注定失败。名字是稳定的，直接按名字找更可靠。
     */
    public BluetoothDevice getFoundByName(String name) {
        return findByName(name);
    }

    /**
     * 「期望地址 → 名字」表。
     *
     * ShareActivity 在列出缓存设备（以及点选设备）时登记，
     * 这样即使对方换了随机 BLE 地址，也能按名字锁定它（P083）。
     */
    private final java.util.Map<String, String> wantedNames =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 登记"这个地址对应的设备叫什么"，供随机地址兜底。 */
    public void rememberName(String address, String name) {
        if (address != null && name != null && !name.isEmpty()) {
            wantedNames.put(address, name.trim());
        }
    }

    /** 已扫到的设备名（来自广播 service data）。 */
    public String nameOf(String address) {
        if (address == null) return null;
        return names.get(address.toUpperCase(java.util.Locale.ROOT));
    }

    /** 已发现设备数量。 */
    public int foundCount() { return found.size(); }

    // ---- 连接 + 读取 ----

    /** 连接目标设备并读取 DeviceInfo。 */
    @SuppressWarnings("MissingPermission")
    public DeviceInfo connectAndRead(BluetoothDevice device, int timeoutSec) throws Exception {
        this.target = device;
        stopScan();

        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IllegalStateException("connectGatt 返回 null");

        if (!infoLatch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new IllegalStateException("读取 DeviceInfo 超时（" + timeoutSec + "s）");
        }
        return deviceInfo.get();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "连接状态: " + newState + " status=" + status);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                state("已连接，发现服务…");
                // 先协商 MTU，再发现服务（MTU 越大分片越少）
                try { g.requestMtu(517); } catch (Throwable ignored) {}
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                state("已断开");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                writeChunkSize = mtu - 3;
                Log.i(TAG, "MTU 协商为 " + mtu + "，分片大小 " + writeChunkSize);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "写分片失败 status=" + status);
                finishWrite(status);
                return;
            }
            byte[] payload = _writePayload;
            int next = _writeOffset + Math.min(writeChunkSize, payload.length - _writeOffset);
            if (next >= payload.length) {
                Log.i(TAG, "P2pInfo 写完了（" + payload.length + " 字节）");
                finishWrite(BluetoothGatt.GATT_SUCCESS);
                return;
            }
            writeChunk(g, ch, payload, next);
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state("服务发现失败: " + status);
                return;
            }
            BluetoothGattService svc = g.getService(BleUtils.SERVICE_UUID);
            if (svc == null) { state("未找到目标 service"); return; }

            BluetoothGattCharacteristic ch = svc.getCharacteristic(BleUtils.CHAR_STATUS_UUID);
            if (ch == null) { state("未找到 CHAR_STATUS"); return; }

            Log.i(TAG, "服务已发现，开始读 DeviceInfo");
            state("读取设备信息…");
            g.readCharacteristic(ch);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "读失败 status=" + status);
                return;
            }
            byte[] value = ch.getValue();
            if (value == null || value.length == 0) return;

            String text = new String(value, java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "读到 " + value.length + " 字节: " + text);

            // 分块：若返回长度等于 MTU-3 且 JSON 不完整，继续按 offset 读
            if (!looksComplete(text)) {
                int next = text.length();
                Log.i(TAG, "JSON 不完整，继续从 offset=" + next + " 读");
                g.readCharacteristic(ch);   // 触发下一次（部分 ROM 自动递增 offset）
                return;
            }

            try {
                DeviceInfo info = DeviceInfo.fromJson(text);
                deviceInfo.set(info);
                if (listener != null) listener.onDeviceInfo(g.getDevice(), info);
                infoLatch.countDown();
            } catch (Throwable t) {
                Log.e(TAG, "解析 DeviceInfo 失败", t);
                state("解析失败: " + t.getMessage());
            }
        }
    };

    /** 粗略判断 JSON 是否完整（花括号配平）。 */
    private static boolean looksComplete(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth == 0 && s.trim().endsWith("}");
    }

    /** 把加密后的 P2pInfo 写入对端 CHAR_P2P。 */
    @SuppressWarnings("MissingPermission")
    public void writeP2pInfo(String json, int timeoutSec) throws Exception {
        if (gatt == null) throw new IllegalStateException("尚未连接");
        BluetoothGattService svc = gatt.getService(BleUtils.SERVICE_UUID);
        if (svc == null) throw new IllegalStateException("无目标 service");
        BluetoothGattCharacteristic ch = svc.getCharacteristic(BleUtils.CHAR_P2P_UUID);
        if (ch == null) throw new IllegalStateException("无 CHAR_P2P");

        byte[] payload = json.getBytes("UTF-8");
        Log.i(TAG, "写 P2pInfo " + payload.length + " 字节");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Integer> statusRef = new AtomicReference<>(null);
        writeLatch = latch;
        writeStatus = statusRef;

        writeChunk(gatt, ch, payload, 0);
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw new IllegalStateException("写 P2pInfo 超时");
        }
        Integer st = statusRef.get();
        if (st == null || st != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException("写 P2pInfo 失败 status=" + st);
        }
    }

    private volatile CountDownLatch writeLatch;
    private volatile AtomicReference<Integer> writeStatus;

    /** 分块写：每次最多 MTU-3 字节，靠 onCharacteristicWrite 回调驱动下一片。 */
    @SuppressWarnings("MissingPermission")
    private void writeChunk(BluetoothGatt g, BluetoothGattCharacteristic ch,
                            byte[] payload, int offset) {
        int chunk = Math.max(1, writeChunkSize);
        _writeOffset = offset;
        _writePayload = payload;

        int len = Math.min(chunk, payload.length - offset);
        byte[] part = new byte[len];
        System.arraycopy(payload, offset, part, 0, len);
        ch.setValue(part);
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = g.writeCharacteristic(ch, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothGatt.GATT_SUCCESS;
        } else {
            ok = g.writeCharacteristic(ch);
        }
        Log.i(TAG, "  写分片 offset=" + offset + " len=" + len + " ok=" + ok);
    }

    private volatile byte[] _writePayload;
    private volatile int _writeOffset;
    private volatile int writeChunkSize = 20;

    private void finishWrite(int status) {
        AtomicReference<Integer> s = writeStatus;
        CountDownLatch l = writeLatch;
        if (s != null) s.set(status);
        if (l != null) l.countDown();
    }

    @SuppressWarnings("MissingPermission")
    public void close() {
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Throwable ignored) {}
        gatt = null;
    }

    private void state(String m) {
        if (listener != null) listener.onState(m);
        Log.i(TAG, m);
    }
}
