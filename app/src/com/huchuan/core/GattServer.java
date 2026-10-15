package com.huchuan.core;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.huchuan.model.DeviceInfo;
import com.huchuan.model.P2pInfo;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接收方的 BLE GATT 服务端。
 *
 * 职责：
 *   1. 广播 ADV_SERVICE_UUID，让发送方能扫到
 *   2. 提供 CHAR_STATUS（只读）→ 发送方读走我们的 DeviceInfo（含公钥）
 *   3. 提供 CHAR_P2P（可写）→ 发送方写入 P2pInfo（SSID/PSK/port）
 *
 * ⚠️ 关键：属性长度可能超过 ATT MTU（默认 23 字节），必须支持按 offset 分块。
 *    读：onCharacteristicReadRequest 用 offset 切片返回
 *    写：用 writeRequests 累积（见 P018）
 */
public final class GattServer {

    private static final String TAG = "HuChuan.GattServer";

    /** ATT 错误码 */
    private static final int GATT_SUCCESS = 0;
    private static final int GATT_READ_NOT_PERMITTED = 0x02;
    private static final int GATT_WRITE_NOT_PERMITTED = 0x03;
    private static final int GATT_INVALID_OFFSET = 0x07;

    public interface Listener {
        /** 收到发送方写入的 P2pInfo（已解密，若带 key）。 */
        void onP2pInfo(P2pInfo info);
        /** 状态变化（用于 UI 反馈）。 */
        void onState(String msg);
    }

    private final Context ctx;
    private final Listener listener;
    private final BluetoothManager btManager;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;

    /** 对外暴露的设备信息（发送方会读这个）。 */
    private volatile byte[] localDeviceBytes;
    private final Object deviceLock = new Object();

    /** 分块写入累积区：key = device+requestId */
    private final ConcurrentHashMap<String, WriteBuf> writeBufs = new ConcurrentHashMap<>();

    private static final class WriteBuf {
        final byte[] data = new byte[4096];
        int length;
    }

    public GattServer(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
        this.btManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager == null) throw new IllegalStateException("无蓝牙服务");
        updateDeviceInfo(0);
    }

    /** 更新对外暴露的 DeviceInfo。state: 0=空闲 1=传输中 */
    public void updateDeviceInfo(int state) {
        DeviceInfo info = new DeviceInfo(state, BleSecurity.getEncodedPublicKey(),
                "02:00:00:00:00:00", 7);
        try {
            synchronized (deviceLock) {
                localDeviceBytes = info.toJson().toString().getBytes("UTF-8");
            }
        } catch (Exception e) {
            Log.e(TAG, "序列化 DeviceInfo 失败", e);
        }
    }

    // ---- 生命周期 ----

    @SuppressWarnings("MissingPermission")
    public void start() throws Exception {
        if (btManager.getAdapter() == null || !btManager.getAdapter().isEnabled()) {
            throw new IllegalStateException("蓝牙未开启");
        }

        // 1. 建 GATT server
        server = btManager.openGattServer(ctx, callback);
        if (server == null) throw new IllegalStateException("openGattServer 返回 null");

        BluetoothGattService svc = new BluetoothGattService(
                BleUtils.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // CHAR_STATUS：只读，暴露 DeviceInfo
        BluetoothGattCharacteristic statusChar = new BluetoothGattCharacteristic(
                BleUtils.CHAR_STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        svc.addCharacteristic(statusChar);

        // CHAR_P2P：可写，接收 P2pInfo
        BluetoothGattCharacteristic p2pChar = new BluetoothGattCharacteristic(
                BleUtils.CHAR_P2P_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        svc.addCharacteristic(p2pChar);

        if (!server.addService(svc)) throw new IllegalStateException("addService 失败");
        Log.i(TAG, "GATT service 已注册");

        // 2. 开始广播
        advertiser = btManager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) throw new IllegalStateException("设备不支持 BLE 广播");

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)          // 0 = 一直广播
                .build();

        // 注意：广播包上限 31 字节，只放 UUID，不放自定义数据
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BleUtils.ADV_SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
        Log.i(TAG, "BLE 广播已启动");
        state("广播中");
    }

    @SuppressWarnings("MissingPermission")
    public void stop() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Throwable ignored) {}
        try {
            if (server != null) { server.clearServices(); server.close(); }
        } catch (Throwable ignored) {}
        server = null;
        advertiser = null;
        Log.i(TAG, "GATT server 已停止");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            Log.i(TAG, "广播成功启动");
            state("广播中");
        }
        @Override public void onStartFailure(int errorCode) {
            Log.e(TAG, "广播启动失败: " + errorCode);
            state("广播失败: " + advertiseError(errorCode));
        }
    };

    private static String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "数据过大(>31B)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "广播实例过多";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "已在广播";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "内部错误";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "不支持";
            default: return "code " + code;
        }
    }

    // ---- GATT 回调 ----

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic ch) {
            if (!BleUtils.CHAR_STATUS_UUID.equals(ch.getUuid())) {
                Log.w(TAG, "读取了未知特征: " + ch.getUuid());
                respond(device, requestId, GATT_READ_NOT_PERMITTED, null);
                return;
            }
            byte[] snapshot;
            synchronized (deviceLock) { snapshot = localDeviceBytes; }
            if (snapshot == null) { respond(device, requestId, GATT_READ_NOT_PERMITTED, null); return; }
            if (offset > snapshot.length) { respond(device, requestId, GATT_INVALID_OFFSET, null); return; }

            byte[] piece = Arrays.copyOfRange(snapshot, offset, snapshot.length);
            Log.i(TAG, "读请求 offset=" + offset + " 返回 " + piece.length + " 字节");
            respond(device, requestId, GATT_SUCCESS, piece);
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic ch,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (!BleUtils.CHAR_P2P_UUID.equals(ch.getUuid())) {
                if (responseNeeded) respond(device, requestId, GATT_WRITE_NOT_PERMITTED, null);
                return;
            }

            String key = device.getAddress() + "#" + requestId;
            WriteBuf buf = writeBufs.get(key);
            if (buf == null) { buf = new WriteBuf(); writeBufs.put(key, buf); }

            if (offset + value.length > buf.data.length) {
                Log.e(TAG, "写入超出缓冲: offset=" + offset + " len=" + value.length);
                writeBufs.remove(key);
                if (responseNeeded) respond(device, requestId, GATT_INVALID_OFFSET, null);
                return;
            }
            System.arraycopy(value, 0, buf.data, offset, value.length);
            buf.length = Math.max(buf.length, offset + value.length);

            Log.i(TAG, "写请求 offset=" + offset + " len=" + value.length
                    + " prepared=" + preparedWrite + " 累计=" + buf.length);

            if (responseNeeded) respond(device, requestId, GATT_SUCCESS, null);

            // preparedWrite = 分块中，继续等；否则是完整数据
            if (preparedWrite) return;

            writeBufs.remove(key);
            byte[] full = Arrays.copyOfRange(buf.data, 0, buf.length);
            handleP2pWrite(full);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            // 订阅通知时会走到这里，必须回应，否则发送方会超时
            if (responseNeeded) respond(device, requestId, GATT_SUCCESS, null);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "连接状态: " + device.getAddress() + " → " + newState);
            state(newState == BluetoothGatt.STATE_CONNECTED ? "已连接" : "已断开");
        }
    };

    @SuppressWarnings("MissingPermission")
    private void respond(BluetoothDevice device, int requestId, int status, byte[] value) {
        try {
            if (server != null) server.sendResponse(device, requestId, status, 0, value);
        } catch (Throwable t) {
            Log.e(TAG, "sendResponse 失败", t);
        }
    }

    /** 收到完整 P2pInfo：如有 key 则解密。 */
    private void handleP2pWrite(byte[] data) {
        try {
            String text = new String(data, "UTF-8");
            Log.i(TAG, "收到 P2pInfo: " + text);
            P2pInfo info = P2pInfo.fromJson(text);

            if (info.key != null) {
                BleSecurity.SessionCipher cipher = BleSecurity.deriveSessionKey(info.key);
                info = new P2pInfo(
                        BleUtils.getSenderId(),
                        cipher.decrypt(info.ssid),
                        cipher.decrypt(info.psk),
                        cipher.decrypt(info.mac),
                        info.port,
                        null,
                        info.catShare);
                Log.i(TAG, "已解密: " + info.describe());
            }
            listener.onP2pInfo(info);
        } catch (Throwable t) {
            Log.e(TAG, "解析 P2pInfo 失败", t);
            state("解析失败: " + t.getMessage());
        }
    }

    private void state(final String msg) {
        if (listener != null) listener.onState(msg);
    }
}
