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

    /** 会话 ID，4 位十六进制。 */
    public static String getSenderId() {
        int raw = (RANDOM_DATA[0] << 8) | (RANDOM_DATA[1] & 0xFF);
        return String.format("%04x", raw & 0xFFFF);
    }

    private BleUtils() {}
}
