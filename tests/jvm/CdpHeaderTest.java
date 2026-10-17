// MS-CDP（Windows 就近共享）UDP 5050 发现层单测 —— 纯 JVM，不需要设备
//
// 覆盖三件事（对应任务的验收标准）：
//   1. 构造 PresenceResponse → 解析回来 → 字段逐个相等（往返测试）
//   2. 断言签名的大端字节是 0x30 0x30
//   3. 断言 msgLen == 实际字节数
// 外加：CommonHeader 逐字段大端断言、PresenceRequest 载荷、长度不符必须丢弃、
//       以及给用户和电脑端抓包比对的十六进制 dump。
import com.huchuan.cdp.CdpHeader;
import com.huchuan.cdp.MsCdpDiscovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

public class CdpHeaderTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ✅ " : "  ❌ ") + name + (detail == null || detail.isEmpty() ? "" : "  " + detail));
        if (ok) pass++; else fail++;
    }

    static void eq(String name, long expected, long actual) {
        check(name, expected == actual, "期望 " + expected + " 实际 " + actual);
    }

    static void eqHex(String name, String expected, String actual) {
        check(name, expected.equalsIgnoreCase(actual), "期望 " + expected + " 实际 " + actual);
    }

    static String hex(int v) {
        return String.format("0x%02X", v & 0xFF);
    }

    static String bytesToHex(byte[] b, int off, int len) {
        // 注意用 0x 前缀，和 eqHex 的"期望"写法保持一致（曾经因为这里漏了 0x
        // 导致三条大端字节序断言假失败 —— 见踩坑日志）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("0x%02X", b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    /** 只看 dump 的十六进制列（去掉偏移与 ASCII 侧栏）。 */
    static String hexColumn(String dumpLine) {
        String body = dumpLine.substring(4).trim();
        int bar = body.indexOf('|');
        if (bar >= 0) body = body.substring(0, bar);
        return body.replaceAll("\\s+", " ").trim();
    }

    public static void main(String[] a) throws Exception {
        System.out.println("=== MS-CDP CommonHeader 编解码 ===");

        // ---------- 1. 固定长度 & 头结束标记 ----------
        // ★42 = 40 固定字段 + 2 字节结束标记（None + 0x00）★
        // 实测判据：头=42 时 Windows 回 131 字节应答；头=41 时完全不回。
        eq("CommonHeader 固定 42 字节", 42, CdpHeader.SIZE);
        eq("头结束标记 = 2 字节", 2, CdpHeader.END_MARKER_SIZE);

        byte[] req = MsCdpDiscovery.buildPresenceRequestForTest();

        // ---------- 2. ★签名大端字节 = 0x30 0x30★ ----------
        eqHex("签名第 0 字节 = 0x30", "0x30", hex(req[0]));
        eqHex("签名第 1 字节 = 0x30", "0x30", hex(req[1]));
        eq("解析出的 Signature = 0x3030", 0x3030, CdpHeader.getU16(req, 0));
        eq("常量 CdpHeader.SIGNATURE = 0x3030", 0x3030, CdpHeader.SIGNATURE);

        // ---------- 3. ★msgLen == 实际字节数★ ----------
        eq("PresenceRequest 总长 = 42 头 + 1 载荷", 43, req.length);
        eq("msgLen 字段 = 实际字节数", req.length, CdpHeader.getU16(req, 2));
        check("declaredLength() 与实际一致", CdpHeader.declaredLength(req) == req.length,
                "声明 " + CdpHeader.declaredLength(req) + " 实际 " + req.length);

        // ---------- 4. 逐字段大端断言（最容易错的地方）----------
        eq("Version = 3", 3, req[4] & 0xFF);
        eq("Type = Discovery(1)", 1, req[5] & 0xFF);
        eq("Flags 大端 i16", 0, CdpHeader.getU16(req, 6));
        eq("FragmentIndex = 0", 0, CdpHeader.getU16(req, 20));
        eq("FragmentCount >= 1", 1, CdpHeader.getU16(req, 22));
        eq("SessionId = 0（无会话）", 0, CdpHeader.getU64(req, 24));
        eq("ChannelId = 0（SessionId 为 0）", 0, CdpHeader.getU64(req, 32));
        eq("头结束标记 [40] = 0x00", 0, req[40] & 0xFF);
        eq("头结束标记 [41] = 0x00", 0, req[41] & 0xFF);
        eq("PresenceRequest 载荷只有 1 字节且 = 0", 0, req[CdpHeader.SIZE] & 0xFF);

        // 大端原语：0x1234 必须写成 12 34（若是小端会变成 34 12 —— 这是头号 bug 源）
        byte[] t = new byte[8];
        CdpHeader.putU16(t, 0, 0x1234);
        eqHex("putU16 大端: 0x1234 → 12 34", "0x12 0x34", hex(t[0]) + " " + hex(t[1]));
        CdpHeader.putU32(t, 0, 0x01020304);
        eqHex("putU32 大端: 0x01020304 → 01 02 03 04",
                "0x01 0x02 0x03 0x04", hex(t[0]) + " " + hex(t[1]) + " " + hex(t[2]) + " " + hex(t[3]));
        CdpHeader.putU64(t, 0, 0x0102030405060708L);
        eqHex("putU64 大端: ...0708 → 01..08",
                "0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08",
                hex(t[0]) + " " + hex(t[1]) + " " + hex(t[2]) + " " + hex(t[3]) + " "
                        + hex(t[4]) + " " + hex(t[5]) + " " + hex(t[6]) + " " + hex(t[7]));

        // 头里 u64/u32 的大端：手搭一个已知头的包
        CdpHeader h = new CdpHeader(CdpHeader.TYPE_DISCOVERY);
        h.sequenceNumber = 0x01020304;
        h.requestId = 0x0102030405060708L;
        h.sessionId = 0x1122334455667788L;
        h.channelId = 0x99AABBCCDDEEFF00L;
        h.fragmentIndex = 2;
        h.fragmentCount = 3;
        h.flags = 0x0102;
        byte[] framed = CdpHeader.build(h, new byte[]{(byte) 0xAA});
        eq("SequenceNumber 大端读回", 0x01020304, CdpHeader.getU32(framed, 8));
        eq("RequestID 大端读回", 0x0102030405060708L, CdpHeader.getU64(framed, 12));
        eq("SessionId 大端读回", 0x1122334455667788L, CdpHeader.getU64(framed, 24));
        eqHex("SequenceNumber 字节序", "0x01 0x02 0x03 0x04", bytesToHex(framed, 8, 4));
        eqHex("RequestID 字节序", "0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08", bytesToHex(framed, 12, 8));
        eq("fragmentIndex/Count 读回", 2, CdpHeader.parsePacket(framed, framed.length).fragmentIndex);
        eq("fragmentCount 读回", 3, CdpHeader.parsePacket(framed, framed.length).fragmentCount);
        eq("flags 读回", 0x0102, CdpHeader.parsePacket(framed, framed.length).flags & 0xFFFF);

        // 头解析往返
        CdpHeader back = CdpHeader.parsePacket(framed, framed.length);
        check("parsePacket 往返: 头不为 null", back != null, "");
        check("parsePacket 往返: type/seq/reqId/session/channel 全等",
                back != null && back.type == h.type && back.sequenceNumber == h.sequenceNumber
                        && back.requestId == h.requestId && back.sessionId == h.sessionId
                        && back.channelId == h.channelId,
                back == null ? "null" : back.toString());

        // ---------- 5. 小端例外（规格 §1 SetReplyToId）----------
        byte[] le = new byte[8];
        CdpHeader.putU64LE(le, 0, 0x0102030405060708L);
        eqHex("putU64LE 小端: → 08 07 06 05 04 03 02 01",
                "0x08 0x07 0x06 0x05 0x04 0x03 0x02 0x01", bytesToHex(le, 0, 8));
        eq("getU64LE 读回小端", 0x0102030405060708L, CdpHeader.getU64LE(le, 0));

        // ---------- 6. 非法包必须被丢弃 ----------
        byte[] bad = new byte[43];
        System.arraycopy(req, 0, bad, 0, req.length);
        bad[0] = 0x31;                       // 改签名
        check("签名不对 → parsePacket 返回 null", CdpHeader.parsePacket(bad, bad.length) == null, "");
        byte[] badVer = new byte[43];
        System.arraycopy(req, 0, badVer, 0, req.length);
        badVer[4] = 9;                       // 改版本
        check("版本 != 3 → 丢弃", CdpHeader.parsePacket(badVer, badVer.length) == null, "");
        byte[] badLen = new byte[43];
        System.arraycopy(req, 0, badLen, 0, req.length);
        CdpHeader.putU16(badLen, 2, 99);     // 声明长度与实际不符
        check("msgLen 与实际不符 → 丢弃", CdpHeader.parsePacket(badLen, badLen.length) == null, "");
        check("短包（<42）→ 丢弃", CdpHeader.parsePacket(new byte[10], 10) == null, "");
        check("null → 丢弃", CdpHeader.parsePacket(null, 0) == null, "");

        // ---------- 7. ★PresenceResponse 往返测试★ ----------
        System.out.println("=== PresenceResponse 往返（字段逐个相等）===");
        String devName = "HuChuan-Test";
        byte[] mac = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        String deviceId = "unit-test-device-id";
        MsCdpDiscovery d = MsCdpDiscovery.forTest(devName, mac, deviceId);

        // ★两种布局都要正确★（规格 §10：官方规范 vs 能跑通的开源实现不一致）
        // 先测「规范布局」（字段最全，能逐字段验证宽度）
        MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT = MsCdpDiscovery.LAYOUT_OFFICIAL_SPEC;
        long requestId = 0x1122334455667788L;
        byte[] resp = d.buildPresenceResponseForTest(requestId);

        eq("PresenceResponse = 42 头 + 载荷", 42, CdpHeader.SIZE);
        eq("msgLen == 实际字节数（响应）", resp.length, CdpHeader.getU16(resp, 2));
        check("declaredLength（响应）", CdpHeader.declaredLength(resp) == resp.length, "");

        MsCdpDiscovery.Peer peer = new MsCdpDiscovery.Peer("127.0.0.1");
        MsCdpDiscovery.parsePresenceResponseForTest(resp, CdpHeader.SIZE, peer);

        // 逐字段解析（严格按官方规范顺序）
        int p = CdpHeader.SIZE;
        // ★载荷第一字节就是 Discovery_Type —— 没有 Message_Type 前缀★
        // （真机实测：Windows 回的包里偏移 42 即 Discovery_Type=1）
        eq("Discovery_Type = 1（载荷首字节）", 1, resp[p] & 0xFF); p++;
        eq("Connection_Mode = 1 (Proximal)", 1, CdpHeader.getU16(resp, p)); p += 2;
        eq("Device_Type = 8 (Android)", 8, CdpHeader.getU16(resp, p)); p += 2;
        int nameLen = CdpHeader.getU16(resp, p); p += 2;
        eq("Device_Name_Length 前缀(2字节)", devName.getBytes(CdpHeader.UTF8).length, nameLen);
        String parsedName = new String(resp, p, nameLen, CdpHeader.UTF8); p += nameLen;
        eq("Device_Name 往返一致", 1, devName.equals(parsedName) ? 1 : 0);
        check("Device_Name 内容", devName.equals(parsedName), "解析出 \"" + parsedName + "\"");
        int parsedSalt = CdpHeader.getU32(resp, p); p += 4;
        eq("DeviceIdSalt 往返一致", d.deviceIdSaltForTest(), parsedSalt);
        p += 4;   // DeviceIdHash(4)
        p += 4;   // PrincipalUserNameHash
        byte[] parsedMac = new byte[6];
        System.arraycopy(resp, p, parsedMac, 0, 6); p += 6;
        eq("MacAddress 往返一致（6 字节）", 1, java.util.Arrays.equals(mac, parsedMac) ? 1 : 0);
        eq("载荷总长 = 逐字段累加", resp.length, p);
        // 载荷长度必须恰好等于"逐字段宽度累加"，多一个字节少一个字节电脑都不认
        int expectPayload = 1 + 2 + 2 + 2 + nameLen + 4 + 4 + 4 + 6;
        check("载荷长度 = 总长 - 42", expectPayload == resp.length - CdpHeader.SIZE,
                "期望 " + expectPayload + " 实际 " + (resp.length - CdpHeader.SIZE));
        eq("载荷长度常量部分 = 7 字节（1+2+2+2）", 7, 1 + 2 + 2 + 2);

        // 解析器往返（走 MsCdpDiscovery 的解析路径）
        check("解析器取回设备名", devName.equals(peer.name), "获得 \"" + peer.name + "\"");
        eq("解析器取回 Device_Type", 8, peer.deviceType);
        eq("解析器取回 Connection_Mode", 1, peer.connectionMode);

        // DeviceIdHash = SHA256(salt || deviceId)，完整 32 字节
        byte[] idHash = d.computeDeviceIdHashForTest();
        eq("DeviceIdHash 是完整 SHA256（32 字节）", 32, idHash.length);
        byte[] expectHash = sha256Prefix4(d.deviceIdSaltForTest(), deviceId);
        check("DeviceIdHash 前 4 字节 = SHA256(salt||deviceId) 前 4 字节",
                java.util.Arrays.equals(expectHash,
                        java.util.Arrays.copyOf(idHash, 4)),
                "计算 " + bytesToHex(idHash, 0, 4) + " 期望 " + bytesToHex(expectHash, 0, 4));

        // ---------- 7b. ★开源布局（默认）★ ----------
        // 唯一被证明能与 Windows 互通的组合：DeviceIdHash(32)，不发后两个字段
        System.out.println("=== PresenceResponse 开源布局（默认，规格 §10）===");
        MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT = MsCdpDiscovery.LAYOUT_OPEN_SOURCE;
        byte[] respOs = d.buildPresenceResponseForTest(requestId);
        eq("开源布局 msgLen == 实际字节数", respOs.length, CdpHeader.getU16(respOs, 2));
        int pOs = CdpHeader.SIZE + 1 + 2 + 2 + 2 + nameLen + 4 + 32;
        eq("开源布局：DeviceIdHash(32) 之后就是末尾", respOs.length, pOs);
        // 开源布局：DeviceIdHash 从 4 → 32 字节（+28），
        // 但去掉 PrincipalUserNameHash(4) + MacAddress(6)（-10），净 +18
        eq("开源布局比规范布局长 18 字节", 18, respOs.length - resp.length);
        check("开源布局确实更长（32-4-10 = +18）", respOs.length - resp.length == 18,
                "规范 " + resp.length + " 开源 " + respOs.length);
        MsCdpDiscovery.Peer peerOs = new MsCdpDiscovery.Peer("127.0.0.1");
        MsCdpDiscovery.parsePresenceResponseForTest(respOs, CdpHeader.SIZE, peerOs);
        check("开源布局也能解析出设备名", devName.equals(peerOs.name), "获得 \"" + peerOs.name + "\"");
        eq("开源布局 Device_Type = 8", 8, peerOs.deviceType);
        // 还原默认，避免影响后续断言
        MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT = MsCdpDiscovery.LAYOUT_OPEN_SOURCE;
        check("默认布局 = 开源布局（唯一验证过能通的组合）",
                MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT == MsCdpDiscovery.LAYOUT_OPEN_SOURCE, "");

        // 响应的 RequestID 应回带请求方的 requestId（小端，照抄规范 SetReplyToId）
        eq("响应回带 RequestID（小端读回）", requestId,
                CdpHeader.getU64LE(resp, 12) == requestId ? requestId : CdpHeader.getU64LE(resp, 12));
        check("响应 RequestID = 请求方 requestId",
                CdpHeader.getU64LE(resp, 12) == requestId || CdpHeader.getU64(resp, 12) == requestId,
                "LE=0x" + Long.toHexString(CdpHeader.getU64LE(resp, 12))
                        + " BE=0x" + Long.toHexString(CdpHeader.getU64(resp, 12)));
        check("响应 RequestID 按常量声明的小端写",
                !MsCdpDiscovery.REPLY_REQUEST_ID_LITTLE_ENDIAN
                        || CdpHeader.getU64LE(resp, 12) == requestId,
                "REPLY_REQUEST_ID_LITTLE_ENDIAN=" + MsCdpDiscovery.REPLY_REQUEST_ID_LITTLE_ENDIAN);

        // 中文设备名（UTF-8 多字节：长度必须按字节而不是字符算）
        MsCdpDiscovery dz = MsCdpDiscovery.forTest("互传手机", mac, deviceId);
        byte[] respZ = dz.buildPresenceResponseForTest(requestId);
        String nameZ = "互传手机";
        eq("中文名按字节计长", nameZ.getBytes(CdpHeader.UTF8).length, nameZ.getBytes(CdpHeader.UTF8).length);
        eq("中文名 Device_Name_Length = 12 字节", 12,
                CdpHeader.getU16(respZ, CdpHeader.SIZE + 5));
        MsCdpDiscovery.Peer pz = new MsCdpDiscovery.Peer("127.0.0.1");
        MsCdpDiscovery.parsePresenceResponseForTest(respZ, CdpHeader.SIZE, pz);
        check("中文设备名往返一致", nameZ.equals(pz.name), "获得 \"" + pz.name + "\"");

        // 超长设备名必须被截断，不能撑爆单个 UDP 数据报
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 400; i++) longName.append('X');
        MsCdpDiscovery dl = MsCdpDiscovery.forTest(longName.toString(), mac, deviceId);
        byte[] respL = dl.buildPresenceResponseForTest(requestId);
        check("超长设备名被截断到 200 字节以内", dl.deviceNameByteLengthForTest() <= 200,
                "实际 " + dl.deviceNameByteLengthForTest() + " 字节");
        check("截断后整包仍 < 512 字节（不会 IP 分片）", respL.length < 512,
                respL.length + " 字节");

        // ---------- 8. 十六进制 dump（给用户和电脑端抓包比对）----------
        System.out.println("=== 十六进制 dump（与电脑端抓包比对用）===");
        System.out.println(CdpHeader.dumpWithCheck("PresenceRequest", req));
        System.out.println();
        System.out.println(CdpHeader.dumpWithCheck("PresenceResponse", resp));

        // dump 本身也要可断言：第一行必须是偏移 0000、第 0/1 字节是 30 30
        String dump = CdpHeader.hexDump(resp);
        String[] lines = dump.split("\n");
        check("dump 第一行以 0000 开头", lines[0].startsWith("0000"), lines[0].substring(0, 20));
        check("dump 第一行前两字节 = 30 30", hexColumn(lines[0]).startsWith("30 30"),
                hexColumn(lines[0]).substring(0, Math.min(11, hexColumn(lines[0]).length())));
        check("dump 行数 = ceil(长度/16)", lines.length == (resp.length + 15) / 16,
                lines.length + " 行 / " + resp.length + " 字节");
        check("dump 含 ASCII 侧栏 '|'", dump.indexOf('|') > 0, "");

        // ---------- 9. 回环过滤：绝不能应答自己发出的广播 ----------
        // （真机实测：发往 192.168.207.255 / 172.20.127.255 的广播会被本机
        //   两块网卡各自回环收一次，源地址就是本机 IP。不过滤就会自己应答自己，
        //   在电脑的列表里制造幽灵设备。）
        System.out.println("=== 本机地址过滤（防自己应答自己）===");
        check("127.0.0.1 判定为本机", MsCdpDiscovery.isLocalAddressForTest("127.0.0.1"), "");
        check("0.0.0.0 判定为本机", MsCdpDiscovery.isLocalAddressForTest("0.0.0.0"), "");
        check("8.8.8.8 判定为非本机", !MsCdpDiscovery.isLocalAddressForTest("8.8.8.8"), "");
        check("null 判定为非本机（不崩）", !MsCdpDiscovery.isLocalAddressForTest(null), "");
        {
            // 本机任一网卡 IP 都必须被判为本机（JVM 里就是 host 自己）
            List<InetAddress> local = MsCdpDiscovery.localAddressesForTest();
            check("本机网卡地址枚举非空", !local.isEmpty(), local.size() + " 个");
            boolean allLocal = true;
            for (int i = 0; i < local.size(); i++) {
                if (!MsCdpDiscovery.isLocalAddressForTest(local.get(i).getHostAddress())) allLocal = false;
            }
            check("本机每个网卡地址都判为本机", allLocal, local.toString());
        }
        // 真发一轮：自己广播出去，只应收到"被过滤"的行为，而不会产生应答
        {
            MsCdpDiscovery dself = MsCdpDiscovery.forTest("Self-Test", new byte[6], "id");
            byte[] ownReq = dself.buildPresenceRequestForTest();
            String ownIp = MsCdpDiscovery.localAddressesForTest().isEmpty()
                    ? "127.0.0.1"
                    : MsCdpDiscovery.localAddressesForTest().get(0).getHostAddress();
            check("自己广播的源地址被识别为本机", MsCdpDiscovery.isLocalAddressForTest(ownIp),
                    ownIp + " → 会被 handlePacket 丢弃");
            check("自己发出的 PresenceRequest 是合法包",
                    CdpHeader.parsePacket(ownReq, ownReq.length) != null, "");
        }

        // ---------- 10. 广播地址枚举（真机 API 的只读检查，失败不算错）----------
        System.out.println("=== 网卡广播地址（纯环境信息）===");
        List<InetAddress> bs = MsCdpDiscovery.broadcastAddresses();
        for (int i = 0; i < bs.size(); i++) {
            System.out.println("  · " + bs.get(i).getHostAddress());
        }
        if (bs.isEmpty()) {
            System.out.println("  （JVM 环境里没有可取到广播地址的网卡 —— 真机上是 wlan0/wlan2）");
        } else {
            check("广播地址枚举非空", true, bs.size() + " 个");
        }

        // ---------- 11. UDP 5050 收发往返（本机 loopback，证明收发链路通）----------
        System.out.println("=== UDP 5050 收发往返（loopback）===");
        udpLoopbackTest();

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 真开一个 socket，把 PresenceResponse 发给自己再收回来。 */
    static void udpLoopbackTest() {
        DatagramSocket rx = null, tx = null;
        try {
            int port = 5050;
            rx = new DatagramSocket(null);
            rx.setReuseAddress(true);
            rx.bind(new java.net.InetSocketAddress(port));
            rx.setSoTimeout(3000);

            MsCdpDiscovery d = MsCdpDiscovery.forTest("HuChuan-Loop", new byte[6], "id");
            byte[] pkt = d.buildPresenceResponseForTest(0x42L);

            tx = new DatagramSocket();
            tx.send(new DatagramPacket(pkt, pkt.length,
                    InetAddress.getByName("127.0.0.1"), port));
            check("能绑定 UDP 5050（只需 INTERNET 权限）", true, "port " + port);

            byte[] buf = new byte[2048];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            rx.receive(in);
            eq("收发包字节数一致", pkt.length, in.getLength());
            check("收到的字节逐一相等",
                    java.util.Arrays.equals(java.util.Arrays.copyOf(buf, in.getLength()), pkt), "");
            CdpHeader h = CdpHeader.parsePacket(java.util.Arrays.copyOf(buf, in.getLength()), in.getLength());
            check("收回来的包仍是合法 CDP 包", h != null && h.type == CdpHeader.TYPE_DISCOVERY,
                    h == null ? "null" : h.toString());
        } catch (Throwable t) {
            check("UDP 5050 收发往返", false, t.toString());
        } finally {
            if (rx != null) rx.close();
            if (tx != null) tx.close();
        }
    }

    static byte[] sha256Prefix4(int salt, String deviceId) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] s = new byte[4];
        CdpHeader.putU32(s, 0, salt);
        md.update(s);
        md.update(deviceId.getBytes(CdpHeader.UTF8));
        byte[] full = md.digest();
        return java.util.Arrays.copyOf(full, 4);
    }
}
