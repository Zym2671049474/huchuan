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
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
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
        // 与 CatShare 一致：用 RootMacReader 读真实 p2p0 地址
        String mac = RootMacReader.getP2pMacAddress();
        if (mac == null) mac = "02:00:00:00:00:00";
        DeviceInfo info = new DeviceInfo(state, BleSecurity.getEncodedPublicKey(), mac, 7);
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

        // ★两个特性都必须是 READ|WRITE★（P107）
        //
        // CatShare 的写法（GattServerService.buildGattService）：
        //     BluetoothGattCharacteristic(uuid, 10, 17)
        //   10 = 0b01010 = PROPERTY_READ(0x02) | PROPERTY_WRITE(0x08)
        //   17 = 0b10001 = PERMISSION_READ(0x01) | PERMISSION_READ_ENCRYPTED_MITM(0x10)
        // **两个特性用的是同一组值**，不对称。
        //
        // 我们原来把 STATUS 设成只读、P2P 设成只写。实测后果：
        // 小米15 连上我们 GATT 后（连接状态 → 2）**什么都不读**，
        // 10 秒后自己断开（→ 0）—— 它在找一个能读的属性，没找到就放弃了。
        // 这正是"小米15 搜不到设备"的真实原因。
        int CHAR_PROPS = BluetoothGattCharacteristic.PROPERTY_READ
                       | BluetoothGattCharacteristic.PROPERTY_WRITE;
        int CHAR_PERMS = BluetoothGattCharacteristic.PERMISSION_READ
                       | BluetoothGattCharacteristic.PERMISSION_WRITE;

        BluetoothGattCharacteristic statusChar = new BluetoothGattCharacteristic(
                BleUtils.CHAR_STATUS_UUID, CHAR_PROPS, CHAR_PERMS);
        svc.addCharacteristic(statusChar);

        BluetoothGattCharacteristic p2pChar = new BluetoothGattCharacteristic(
                BleUtils.CHAR_P2P_UUID, CHAR_PROPS, CHAR_PERMS);
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

        // ★主广播包：UUID + 6 字节"品牌/5GHz"条目★（P102）
        //
        // 那个 6 字节条目的**内容没人读**，信息全在 UUID 本身：
        //   UUID = 000001ff-0000-1000-8000-00805f9b34fb
        //   对方把它拆成 16 字节取 msb：
        //     arr[2] == 1  → supports5Ghz = true
        //     arr[3] == ff → brandId（0xff = 未知品牌）
        //
        // ★为什么必须有它★：小米15 的内置「互传」靠这个条目判定
        // "这是一台合格的互传设备"，没有它就不会去读扫描响应里的名字 ——
        // 表现就是名字显示不全（只显示 System Name 的尾巴，如 "Pro"）。
        //
        // catShare 的 advData 也是这么发的（GattServerService.startAdv）。
        // ★UUID 是**动态算出来的**，不是写死的★（P106）
        //
        // 小米互传 v1/c.e：
        //   addServiceData(c(z1.a.j(), v1.c.h(ctx)), copyOfRange(x1.f(),0,6))
        // 实测小米15 广播的 UUID16 = 0x1b1e（不是 0xffff，也不是 001ff）。
        // 之前写死 UUID 导致对方认不出我们 → 显示"未知的设备"。
        // ★主广播 UUID 必须是 0x01ff★（P107 —— 改回 P102 验证过的值）
        //
        // 我曾在 P106 按"小米15 实测 0x1b1e"把它改成动态 UUID，
        // 结果**小米15 直接搜不到我们了** —— 说明 0x1b1e 是
        // 小米私有编码（z1.a.j() 返回的小米内部值），
        // 非小米设备冒用会让对方**在扫描阶段就把我们过滤掉**。
        //
        // 结论：0x01ff 是互传联盟/CatShare 的**公开约定值**，必须用它。
        // 实测对照：改 0x01ff → 能搜到；改 0x1b1e → 搜不到。
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BleUtils.ADV_SERVICE_UUID))
                .addServiceData(ParcelUuid.fromString("000001ff-0000-1000-8000-00805f9b34fb"),
                        BleUtils.brandData())
                .setIncludeDeviceName(false)
                .build();

        // ★扫描响应里带上设备名★（P101）
        //
        // 不带这个的话，对方（小米15 的「互传」）扫到我们只知道有个设备，
        // 拿不到名字 → 列表里显示"未知的设备"。
        // catShare 就是这么做的（GattServerService.startAdv 的 scanRespData），
        // 所以它的效果是能显示出名字。
        //
        // 27 字节布局（与 CatShare 完全一致，也和我们**解析**时用的偏移对应）：
        //   [0..7]  8 个 0
        //   [8..9]  RANDOM_DATA（就是 getSenderId() 的原始两字节）
        //   [10..25] 设备名（UTF-8，最多 16 字节）
        //   [26]    1
        AdvertiseData scanResp = buildScanResponse();

        // 用 startAdvertisingSet 才能带扫描响应（老 API startAdvertising 不支持）
        startAdvertisingSet(settings, data, scanResp);
        Log.i(TAG, "BLE 广播已启动（含扫描响应：名字=" + lastAdvName + "）");
        state("广播中");
    }

    /** 最近一次广播出去的名字（仅用于日志）。 */
    private String lastAdvName;
    /** startAdvertisingSet 返回的句柄，stop 时要显式停掉。 */
    private android.bluetooth.le.AdvertisingSet advSet;

    /**
     * 构造扫描响应数据包（带设备名）。
     *
     * 布局照抄 CatShare，27 字节：
     *   [0..7] 0, [8..9] RANDOM_DATA, [10..25] 名字, [26] 1
     */
    private AdvertiseData buildScanResponse() {
        byte[] d = new byte[27];
        System.arraycopy(BleUtils.RANDOM_DATA, 0, d, 8, 2);

        String name = com.huchuan.core.DeviceName.get(ctx);
        lastAdvName = name;
        byte[] nb = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (nb.length > 15) {
            // 超过 15 字节要**按 UTF-8 字符边界**截断，否则会切出半个汉字
            // （CatShare 的做法：逐字符回退直到是原串的前缀）
            String str = new String(nb, 0, 15, java.nio.charset.StandardCharsets.UTF_8);
            int len = str.length() - 1;
            while (len >= 0 && !name.startsWith(str)) {
                str = str.substring(0, len);
                len--;
            }
            // 截断后补 '	' —— 对方解析时靠它判断"名字被截短了"
            nb = (str + "\t").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        System.arraycopy(nb, 0, d, 10, Math.min(nb.length, 16));
        d[26] = 1;

        // ★扫描响应的 UUID 固定是 0xffff★（P106 实测）
        //
        // 实测小米15 的两条 serviceData **UUID 不同**：
        //   主广播  : UUID16 = 0x1b1e, data = 6 字节
        //   扫描响应: UUID16 = 0xffff, data = 27 字节  ← 名字在这条
        //
        // （一开始我以为两条同 UUID，是错的 —— 拆成两个 31 字节包才看清。）
        return new AdvertiseData.Builder()
                .addServiceData(ParcelUuid.fromString("0000ffff-0000-1000-8000-00805f9b34fb"), d)
                .build();
    }

    /**
     * 启动广播 + 扫描响应。
     *
     * API 26+ 用 startAdvertisingSet（能带 scan response）；
     * 更低版本老 API 不支持 scan response，退回原来的行为。
     */
    @SuppressWarnings("MissingPermission")
    private void startAdvertisingSet(AdvertiseSettings settings,
                                     AdvertiseData data, AdvertiseData scanResp) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                AdvertisingSetParameters params = new AdvertisingSetParameters.Builder()
                        .setLegacyMode(true)
                        .setConnectable(true)
                        .setScannable(true)
                        .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                        .build();
                advertiser.startAdvertisingSet(params, data, scanResp, null, null,
                        0, 0, advSetCallback);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "startAdvertisingSet 失败，退回 startAdvertising: " + t);
            }
        }
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private final android.bluetooth.le.AdvertisingSetCallback advSetCallback =
            new android.bluetooth.le.AdvertisingSetCallback() {
        @Override public void onAdvertisingSetStarted(android.bluetooth.le.AdvertisingSet set,
                                                      int txPower, int status) {
            // ★必须把句柄存下来★（P115）—— 见 stop() 的注释
            advSet = set;
            Log.i(TAG, "广播集已启动 status=" + status + " txPower=" + txPower
                    + " set=" + (set != null));
            state("广播中");
        }
        @Override public void onAdvertisingSetStopped(android.bluetooth.le.AdvertisingSet set) {
            Log.i(TAG, "广播集已停止");
            advSet = null;
        }
        @Override public void onAdvertisingEnabled(android.bluetooth.le.AdvertisingSet set,
                                                    boolean enable, int status) {
            Log.i(TAG, "广播开关 enable=" + enable + " status=" + status);
        }
    };

    @SuppressWarnings("MissingPermission")
    public void stop() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Throwable ignored) {}

        // ★这是"关掉开关还在广播"的真正原因★（P115）
        //
        // 原来写的是：
        //     if (advSet != null && advertiser != null)
        //         advertiser.stopAdvertisingSet(advSetCallback);
        //
        // 但 `advSet` 字段**从来没有被赋过值**（声明在 210 行，只在 305 行被读），
        // 所以 advSet 恒为 null → 这个 if **永远不成立** →
        // stopAdvertisingSet **一次都没被调用过**。
        //
        // 而上面那句 `stopAdvertising(advertiseCallback)` 对
        // `startAdvertisingSet` 启的广播**无效**（那是 legacy API 的停止方法）。
        //
        // 结果：关了接收开关，GATT Server 确实注销了（dumpsys Entries: 0），
        // 但 **BLE 广播一直在发**，对方设备仍然能扫描到本机 ——
        // 用户报的"本机没开接收开关，小米15却扫描到了本机"就是这个。
        //
        // 实测证据（服务已退出，进程还活着）：
        //     ServiceRecord: 0
        //     GATT Server Map: Entries: 0
        //     但广播器那条记录只有 `Elapsed time: 49087ms`，**没有 Stop time** ——
        //     说明它还在广播。
        // 且日志里从来没出现过 onAdvertisingSetStopped 的"广播集已停止"。
        //
        // 修法：stopAdvertisingSet 收的是 **callback**，与 set 句柄无关，
        // 所以**不能**用 advSet 做前置条件，直接调用即可。
        try {
            if (advertiser != null) advertiser.stopAdvertisingSet(advSetCallback);
        } catch (Throwable t) {
            Log.w(TAG, "stopAdvertisingSet 失败", t);
        }
        // 双保险那一步已去掉：实测 stopAdvertisingSet 本身就够用
        // （会回调 onAdvertisingSetStopped → 日志"广播集已停止"，
        //  dumpsys 里也正确出现 Stop time）。
        // 再补一句 enableAdvertising(false,0,0) 只会让蓝牙栈报
        //   BtGatt.AdvertiseManager: enableAdvertisingSet(): Bad advertiserId=0
        // 白白污染日志，所以不做了。
        advSet = null;

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
            // ★两个特征都允许读★（P107）
            //
            // 对方可能读任意一个（catShare 两个都有 READ 属性）。
            // 原来只认 CHAR_STATUS，读 CHAR_P2P 会返回
            // GATT_READ_NOT_PERMITTED(0x02) → 对方直接放弃 → 断开。
            if (!BleUtils.CHAR_STATUS_UUID.equals(ch.getUuid())
                    && !BleUtils.CHAR_P2P_UUID.equals(ch.getUuid())) {
                Log.w(TAG, "读取了未知特征: " + ch.getUuid());
                respond(device, requestId, GATT_READ_NOT_PERMITTED, null);
                return;
            }
            byte[] snapshot;
            synchronized (deviceLock) { snapshot = localDeviceBytes; }
            if (snapshot == null) {
                Log.w(TAG, "localDeviceBytes 还没准备好，读请求失败");
                respond(device, requestId, GATT_READ_NOT_PERMITTED, null);
                return;
            }
            Log.i(TAG, "读请求 uuid=" + ch.getUuid() + " offset=" + offset);
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
