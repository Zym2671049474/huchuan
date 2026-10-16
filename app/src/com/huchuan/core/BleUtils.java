package com.huchuan.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/** BLE 协议常量。UUID 必须与互传联盟/CatShare 完全一致，否则无法互通。 */
public final class BleUtils {
    public static final UUID ADV_SERVICE_UUID  = UUID.fromString("00003331-0000-1000-8000-008123456789");
    public static final UUID SERVICE_UUID      = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_STATUS_UUID  = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_P2P_UUID     = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb");

    public static final byte[] RANDOM_DATA = initRandom();

    private static byte[] initRandom() {
        Random r = new Random();
        return Arrays.copyOfRange(ByteBuffer.allocate(8).putLong(Math.abs(r.nextLong())).array(), 0, 2);
    }

    /**
     * ★按小米互传的规则生成 service data 的 UUID★（P106）
     *
     * 反编译自 `com.miui.mishare.connectivity.v1.c`：
     *   ```java
     *   private static ParcelUuid c(byte hi, byte lo) {
     *       return ParcelUuid.fromString(
     *           String.format("0000%02x%02x-0000-1000-8000-00805f9b34fb", hi, lo));
     *   }
     *   ```
     * 注意 `String.format("%02x", byte)` 对 byte 会**补符号位**，
     * 所以传进来的其实是已经 &0xff 的值。
     *
     * 我们之前写死了 `0000ffff-...`，而小米15 实测广播的是 **0x1b1e** ——
     * 这就是"对方不认我们"的原因（P106）。
     */
    public static java.util.UUID serviceDataUuid(int hi, int lo) {
        return java.util.UUID.fromString(String.format(
                java.util.Locale.ROOT, "0000%02x%02x-0000-1000-8000-00805f9b34fb",
                hi & 0xff, lo & 0xff));
    }

    /**
     * 主广播包里那个 6 字节「品牌 / 5GHz」条目的负载。
     *
     * ★内容其实没人读★ —— 信息编码在**条目对应的 UUID** 里
     * （见 GattServer：000001ff-0000-1000-8000-00805f9b34fb 的
     *  msb 第 3 字节 = supports5Ghz，第 4 字节 = brandId）。
     * 这里按 CatShare 的做法放 RANDOM_DATA 前两字节 + 补零。
     */
    public static byte[] brandData() {
        byte[] d = new byte[6];
        System.arraycopy(RANDOM_DATA, 0, d, 0, Math.min(2, RANDOM_DATA.length));
        return d;
    }

    /** 会话 ID，4 位十六进制。 */
    public static String getSenderId() {
        int raw = (RANDOM_DATA[0] << 8) | (RANDOM_DATA[1] & 0xFF);
        return String.format("%04x", raw & 0xFFFF);
    }

    private BleUtils() {}
}
