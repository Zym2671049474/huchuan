package com.huchuan.cdp;

import java.nio.charset.Charset;

/**
 * MS-CDP（Windows「就近共享」）CommonHeader 编解码。
 *
 * <p>规格来源：微软公开规范 MS-CDP
 * <a href="https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cdp/">learn.microsoft.com/openspecs/windows_protocols/ms-cdp</a>，
 * 以及本仓库 {@code docs/04-参考/MS-CDP协议规格.md} §2。
 *
 * <h3>★字节序：全部大端（BigEndian）★</h3>
 * 这是本协议最容易搞错的地方 —— 参考实现
 * （{@code ShortDev.Microsoft.ConnectedDevices}）用的是
 * {@code EndianReader.FromMemory(Endianness.BigEndian, ...)}。
 * 唯一例外见 {@link #requestIdLe}：{@code SetReplyToId} 写 RequestID 用的是小端，
 * 这是规范自身的不一致处（规格文档 §1 明确要求照抄）。
 *
 * <h3>固定 41 字节布局（无附加头时）★实测反推★</h3>
 * <pre>
 * 偏移 宽度 字段
 *  0    2   Signature        = 0x3030（'0','0'），否则丢弃
 *  2    2   MessageLength    ★整包总长，含签名和头★
 *  4    1   Version          = 3
 *  5    1   Type             MessageType
 *  6    2   Flags            i16
 *  8    4   SequenceNumber   u32
 * 12    8   RequestID        u64
 * 20    2   FragmentIndex    u16，从 0 起
 * 22    2   FragmentCount    u16，>= 1
 * 24    8   SessionId        u64，无会话时 0
 * 32    8   ChannelId        u64，SessionId 为 0 时也 0
 * 40    1   AdditionalHeaderType.None = 0   头结束标记（★仅 1 字节★）
 * </pre>
 *
 * <p><b>本类不做 Android 依赖</b>：纯 JDK 实现，可直接在 JVM 单测里跑
 * （见 {@code tests/jvm/CdpHeaderTest.java}）。
 */
public final class CdpHeader {

    /** 消息签名：ASCII "00"。收到不等于此值的包一律丢弃。 */
    public static final int SIGNATURE = 0x3030;

    /** 协议版本，必须为 3。 */
    public static final int PROTOCOL_VERSION = 3;

    /**
     * 无附加头时 CommonHeader 的字节数 = 42。
     *
     * <p>★= 40 固定字段 + 2 字节结束标记（`None(0)` + `0x00`）★
     *
     * <p>⚠️ 2026-10-16 曾一度改成 41，**那是错的，已回滚**。经过一次
     * 决定性的对照实验（向真机 Windows 电脑各发一次）：
     *
     * <pre>
     * 头 = 42 → 电脑回 131 字节 PresenceResponse   ✅
     * 头 = 41 → 电脑完全无应答                      ❌
     * </pre>
     *
     * <p>反推你电脑回的包也能验证：载荷正好从偏移 **42** 开始
     * （`[42]=01` Discovery_Type、`[43:45]` Connection_Mode=1、
     * `[45:47]` Device_Type=9、`[47:49]` NameLen=6、`[49:55]`="Lenovo"）。
     *
     * <p>当初误改成 41 的原因是：把载荷第一字节当成了 `Message_Type`，
     * 于是"41 对齐"看起来也自洽。**实际上 Discovery 消息的载荷没有
     * `Message_Type` 前缀** —— 直接从 `Discovery_Type` 开始。
     * 教训：字节级对齐有歧义时，**以真实设备的应答为准**，不要靠推断。
     */
    public static final int SIZE = 42;

    /** 头结束标记的字节数 = 2（`AdditionalHeaderType.None(0)` + 0x00）。 */
    public static final int END_MARKER_SIZE = 2;

    /** 头结束标记：AdditionalHeaderType.None(0)。 */
    public static final int END_MARKER_0 = 0x00;

    // ---- MessageType（规格 §2）----
    public static final int TYPE_NONE = 0;
    public static final int TYPE_DISCOVERY = 1;
    public static final int TYPE_CONNECT = 2;
    public static final int TYPE_CONTROL = 3;
    public static final int TYPE_SESSION = 4;
    public static final int TYPE_ACK = 5;
    public static final int TYPE_RELIABILITY_RESPONSE = 6;
    public static final int TYPE_DISCONNECT = 7;

    /** 会话主机标志位（用于区分 host/client 会话），规格 §2。 */
    public static final int SESSION_ID_HOST_FLAG = 0x80000000;

    /** CDP 全协议字符串编码：UTF-8。 */
    public static final Charset UTF8 = Charset.forName("UTF-8");

    // ---- 字段 ----
    public int type = TYPE_DISCOVERY;
    public short flags;
    public int sequenceNumber;
    public long requestId;
    public int fragmentIndex;
    public int fragmentCount = 1;
    public long sessionId;
    public long channelId;

    /**
     * RequestID 的小端开关。
     *
     * <p>默认 {@code false}（大端，与 CDP 其余字段一致）。
     * 官方规范 §1 记载的例外是 {@code SetReplyToId} 用
     * {@code BinaryPrimitives.WriteUInt64LittleEndian} 写 RequestID ——
     * 规格文档要求"照抄"，所以这里做成可切换常量：
     * 默认走大端；若实测电脑不认，把本字段置 true 再试小端。
     */
    public boolean requestIdLe;

    public CdpHeader() {
    }

    /** 便利构造：Discovery 消息头（绝大多数发现包都用这个）。 */
    public CdpHeader(int type) {
        this.type = type;
    }

    // =====================================================================
    // 编码
    // =====================================================================

    /**
     * 把本头写进 {@code buf} 的 {@code off} 处。
     *
     * @param buf     目标缓冲
     * @param off     写入起点
     * @param msgLen  ★整包总长（含 41 字节头）★，必须是调用方算好的真实长度
     * @return 实际写入的字节数（恒为 {@link #SIZE}）
     */
    public int write(byte[] buf, int off, int msgLen) {
        putU16(buf, off + 0, SIGNATURE);
        putU16(buf, off + 2, msgLen);
        buf[off + 4] = (byte) PROTOCOL_VERSION;
        buf[off + 5] = (byte) (type & 0xFF);
        putU16(buf, off + 6, flags & 0xFFFF);
        putU32(buf, off + 8, sequenceNumber);
        if (requestIdLe) putU64LE(buf, off + 12, requestId);
        else putU64(buf, off + 12, requestId);
        putU16(buf, off + 20, fragmentIndex);
        putU16(buf, off + 22, fragmentCount);
        putU64(buf, off + 24, sessionId);
        putU64(buf, off + 32, channelId);
        // 头结束标记 = 2 字节：AdditionalHeaderType.None(0) + 0x00
        // ★实测：只写 1 字节时 Windows 完全不回包★（见 SIZE 的注释）
        buf[off + 40] = (byte) END_MARKER_0;
        buf[off + 41] = 0x00;
        return SIZE;
    }

    /**
     * 生成一个完整消息：CommonHeader(41) + payload，并自动把
     * {@code msgLen} 填成实际总长。
     *
     * @param payload 载荷，可为 null（表示无载荷）
     */
    public static byte[] build(CdpHeader h, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        byte[] out = new byte[SIZE + plen];
        h.write(out, 0, out.length);
        if (plen > 0) System.arraycopy(payload, 0, out, SIZE, plen);
        return out;
    }

    /** 便利：Discovery 消息 + 指定载荷。 */
    public static byte[] buildDiscovery(int type, byte[] payload) {
        CdpHeader h = new CdpHeader(TYPE_DISCOVERY);
        h.type = type;
        return build(h, payload);
    }

    // =====================================================================
    // 解码
    // =====================================================================

    /**
     * 解析 CommonHeader。
     *
     * @return 解析结果；签名/版本/长度非法时返回 {@code null}（调用方必须处理）
     */
    public static CdpHeader parse(byte[] buf, int off, int len) {
        if (buf == null) return null;
        if (len < SIZE) return null;
        if (off < 0 || off + len > buf.length) return null;
        int sig = getU16(buf, off);
        if (sig != SIGNATURE) return null;
        int ver = buf[off + 4] & 0xFF;
        if (ver != PROTOCOL_VERSION) return null;

        CdpHeader h = new CdpHeader();
        h.type = buf[off + 5] & 0xFF;
        h.flags = (short) getU16(buf, off + 6);
        h.sequenceNumber = getU32(buf, off + 8);
        h.requestId = getU64(buf, off + 12);
        h.fragmentIndex = getU16(buf, off + 20);
        h.fragmentCount = getU16(buf, off + 22);
        h.sessionId = getU64(buf, off + 24);
        h.channelId = getU64(buf, off + 32);
        return h;
    }

    /**
     * 解析并校验整包长度。
     *
     * <p>{@code MessageLength} 是"整包总长（含头）"，所以它必须等于 UDP
     * 数据报的实际字节数 —— 不等说明包被截断或拼接，必须丢弃。
     *
     * @return 合法时返回头；否则 null
     */
    public static CdpHeader parsePacket(byte[] packet, int len) {
        CdpHeader h = parse(packet, 0, len);
        if (h == null) return null;
        int msgLen = getU16(packet, 2);
        if (msgLen != len) return null;
        return h;
    }

    /** 从包里读声明长度（供日志比对"声明 vs 实际"）。 */
    public static int declaredLength(byte[] packet) {
        if (packet == null || packet.length < 4) return -1;
        return getU16(packet, 2);
    }

    /** 载荷起点偏移（无附加头时）。 */
    public static int payloadOffset() {
        return SIZE;
    }

    // =====================================================================
    // 大端读写原语（★全部大端★）
    // =====================================================================

    public static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) ((v >>> 8) & 0xFF);
        b[o + 1] = (byte) (v & 0xFF);
    }

    public static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) ((v >>> 24) & 0xFF);
        b[o + 1] = (byte) ((v >>> 16) & 0xFF);
        b[o + 2] = (byte) ((v >>> 8) & 0xFF);
        b[o + 3] = (byte) (v & 0xFF);
    }

    public static void putU64(byte[] b, int o, long v) {
        b[o] = (byte) ((v >>> 56) & 0xFF);
        b[o + 1] = (byte) ((v >>> 48) & 0xFF);
        b[o + 2] = (byte) ((v >>> 40) & 0xFF);
        b[o + 3] = (byte) ((v >>> 32) & 0xFF);
        b[o + 4] = (byte) ((v >>> 24) & 0xFF);
        b[o + 5] = (byte) ((v >>> 16) & 0xFF);
        b[o + 6] = (byte) ((v >>> 8) & 0xFF);
        b[o + 7] = (byte) (v & 0xFF);
    }

    /** 小端 u64 —— 只用于 {@code SetReplyToId} 的那个例外。 */
    public static void putU64LE(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
        b[o + 2] = (byte) ((v >>> 16) & 0xFF);
        b[o + 3] = (byte) ((v >>> 24) & 0xFF);
        b[o + 4] = (byte) ((v >>> 32) & 0xFF);
        b[o + 5] = (byte) ((v >>> 40) & 0xFF);
        b[o + 6] = (byte) ((v >>> 48) & 0xFF);
        b[o + 7] = (byte) ((v >>> 56) & 0xFF);
    }

    public static int getU16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static int getU32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static long getU64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    /** 小端 u64 读回（与 {@link #putU64LE} 配套）。 */
    public static long getU64LE(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    // =====================================================================
    // 调试：十六进制 dump
    // =====================================================================

    /**
     * 打印整包的十六进制 dump —— 用于和电脑端 Wireshark 抓包逐字节比对。
     *
     * <p>格式：{@code offset(4位十六进制)  16 字节空格分隔  |可打印 ASCII|}，
     * 与 {@code hexdump -C} / Wireshark 的观感一致，方便肉眼对拍。
     */
    public static String hexDump(byte[] data, int off, int len) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += 16) {
            sb.append(pad4(Integer.toHexString(off + i))).append("  ");
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < len) {
                    int v = data[off + i + j] & 0xFF;
                    sb.append(String.format("%02x", v)).append(' ');
                    ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
                } else {
                    sb.append("   ");
                    ascii.append(' ');
                }
                if (j == 7) sb.append(' ');
            }
            sb.append(" |").append(ascii).append('|');
            if (i + 16 < len) sb.append('\n');
        }
        return sb.toString();
    }

    /** 同上，从 0 开始。 */
    public static String hexDump(byte[] data) {
        return hexDump(data, 0, data == null ? 0 : data.length);
    }

    private static String pad4(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 4; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /** 把 dump 连同"声明长度 / 实际长度"一起打出来，便于比对。 */
    public static String dumpWithCheck(String label, byte[] packet) {
        int declared = declaredLength(packet);
        StringBuilder sb = new StringBuilder();
        sb.append(label == null ? "packet" : label).append(": 实际 ")
                .append(packet == null ? 0 : packet.length)
                .append(" 字节, MessageLength 声明 ").append(declared);
        if (packet != null && declared == packet.length) sb.append("  ✅ 一致");
        else sb.append("  ❌ 不一致");
        sb.append('\n').append(hexDump(packet));
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CdpHeader{type=" + type + ", flags=" + flags
                + ", seq=" + sequenceNumber + "(" + Integer.toUnsignedString(sequenceNumber) + ")"
                + ", requestId=0x" + Long.toHexString(requestId)
                + ", frag=" + fragmentIndex + "/" + fragmentCount
                + ", session=0x" + Long.toHexString(sessionId)
                + ", channel=0x" + Long.toHexString(channelId)
                + ", requestIdLe=" + requestIdLe + "}";
    }
}
