package com.huchuan.core;

/**
 * 本机在「互传协议」里的身份参数。
 *
 * 全部对照小米互传 4.12.0 反编译结果实现（见 docs/踩坑日志.md P106）。
 * 这些值决定**对方能不能正确认出我们**，所以不能随便填。
 */
public final class DeviceProfile {

    /**
     * **主广播**里 service data UUID 的高字节。
     *
     * 实测小米15 主广播：UUID16 = 0x1b1e，data = 6 字节。
     * 对应小米互传 `v1/c.e` 的 `c(z1.a.j(), v1.c.h(ctx))`。
     */
    public static int manufactureHi() {
        return 0x1b;
    }

    /**
     * **主广播**里 service data UUID 的低字节 —— 能力位域。
     *
     * 小米互传 `v1.c.h(ctx)`：
     *   int v = 0;
     *   if (NetworkUtils.C(ctx,false)) v |= 0x01;   // bit0 支持 5GHz
     *   if (m1.v(ctx))                 v |= 0x04;   // bit2
     *   if (c0.j())                    v |= 0x08;   // bit3
     *   if (c0.i())                    v |= 0x12;   // bit4|bit1
     *
     * 实测小米15 = 0x1e（bit1|bit2|bit3|bit4）。
     * 我们照抄 0x1e：既表示支持 5GHz 相关能力，也和对方实测值一致，
     * 避免因为位域不同被当成"异构设备"而拒绝解析名字。
     */
    public static int flagsLo() {
        return 0x1e;
    }

    /** 设备类别（deviceCode）。看 DeviceKind.fromCode 的表。 */
    public static int deviceCode() {
        return 1;   // 1 = 手机
    }

    private DeviceProfile() {}
}
