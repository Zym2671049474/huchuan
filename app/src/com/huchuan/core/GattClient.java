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
        void onFound(BluetoothDevice device, int rssi);
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

    private final CountDownLatch infoLatch = new CountDownLatch(1);
    private final AtomicReference<DeviceInfo> deviceInfo = new AtomicReference<>();

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
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.i(TAG, "开始扫描 ADV_SERVICE_UUID");
        state("扫描中…");
    }

    @SuppressWarnings("MissingPermission")
    public void stopScan() {
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            Log.i(TAG, "发现设备: " + d.getAddress() + " rssi=" + result.getRssi());
            lastFound = d;
            lastFound = d;
            if (listener != null) listener.onFound(d, result.getRssi());
        }
        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败: " + errorCode);
            state("扫描失败: code " + errorCode);
        }
    };

    /** 最近一次扫描到的设备（供上层轮询）。 */
    public BluetoothDevice getLastFound() { return lastFound; }

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
