# 小米电脑互联 —— 可行性分析（结论：本项目无法直接适配）

> 调查时间：2026-10-16　调查者：agent
> 触发：用户反馈「小米手机能互传了，但发现不了小米电脑」

## 一句话结论

**小米电脑（「小米互联服务」/ HyperConnect）根本不监听 BLE，
它走的是局域网 mDNS 组播 + 小米私有 Lyra/netbus 栈。**
所以无论怎么改我们的 BLE 广播，电脑都不会发现本机。

---

## 1. 两个完全不同的协议

|  | **互传联盟 / MTA**（本项目已实现）| **小米 PC 互联**（HyperConnect）|
|---|---|---|
| 范围 | 跨品牌 Android ↔ Android | 仅 小米手机 ↔ 小米电脑 |
| **发现方式** | **BLE 广播 + GATT** | **局域网 mDNS 组播（5353/UDP）** |
| 服务名 | 广播 UUID `00003331-…` | `_lyra-mdns._udp.local`（应答）<br>`_mi-connect._udp.local`（查询）|
| 承载 | WiFi Direct (P2P) + WSS + HTTPS | 局域网 / WiFi / 网线 |
| 凭据 | BLE GATT 交换 P2P SSID/PSK/端口 | 小米账号 + 「设备互联」开关 |
| 传输层认证 | p2p0 MAC（死字段）| Lyra/IDM，身份取自**网卡 MAC** |
| 公开实现 | mtapy / CatShare（就是我们参考的）| **无任何公开实现** |

> 「小米妙享」不是独立 App，是这些 App 里的**屏幕镜像功能名**；
> 「小米互传 PC 版」是口语，官方产品名是**「小米互联服务」**。

## 2. 三条证据

### 证据 1：官方文档只要求「同一局域网」，不提蓝牙

> 使用条件：电脑需安装并打开**「小米互联服务」**应用…
> **小米手机与电脑需要处于同一局域网**。…确保小米手机「设置-**设备互联**」开关已开启。
> —— <https://hyperos.mi.com/continuity/abilities/ab0019>

条件是 **同一局域网 + HyperOS 3/4 + 设备互联开关**，**没有蓝牙**。

### 证据 2：实测抓包看到 mDNS，且明确「不再需要蓝牙模块」

Wireshark 抓包显示组播查询与应答：

```
_mi-connect._udp.local          (QU 查询)
_lyra-mdns._udp.local           ← 手机用这个应答
3F2E52BE._lyra-mdns._udp.local. can be reached at 3F2E52BE.local.:5353
  AppData=<base64 protobuf: 设备名 "Redmi K50", 网络信息>
  MediumType=64
  DebugInfo={msg:reply, ifname:ap0, v4:192.168.x.46, ...}
```

全是 IP 层信息（`ifname`、IPv4/v6、5353 端口）。
第三方对比评测也直说：互联 PC 版「传输文件**不再需要 WiFi 蓝牙模块**，仅需同一局域网」。

### 证据 3：PC 端栈是私有的，且有系统签名级门槛

- PC 侧原生模块：`micont_rtm.dll`、`micont_service.exe`、`Lyra/netbus`、`idmruntime.dll`
- 设备身份取自**网络适配器 MAC**（`IfType == 0x47` = IEEE80211），是 IP 层概念
- Android 侧 `小米互传` APK 里，PC 通道在 `com/miui/mishare/connectivity/pc/`，
  但它建立在 `com.xiaomi.continuity.netbus` 之上，需要：
  ```
  com.xiaomi.permission.BIND_CONTINUITY_SERVICE
  com.xiaomi.permission.BIND_CONTINUITY_SERVICE_INTERNAL
  ```
  **签名级系统权限，第三方 App 拿不到**；类原生 ROM 也没有这个服务。

（本机实测：`pm list packages | grep -i continuity` → 空。）

### 补充：连"重放抓到的包"都不够

抓包作者试过直接重放捕获的 mDNS 包，**并没有触发发现** ——
说明后面还有单播心跳/校验。所以「写个 mDNS 假应答」也不是可行捷径。

## 3. 为什么 CatShare 也没做 PC

CatShare 作者在 issue #22（「建议加 Windows 版」）里回复：

> the **public BLE API on Windows does not have support for features required
> by this protocol** … manufacturers that also have laptop products
> (such as Xiaomi and Samsung) often have their PC-side app limited to
> certain model or network card … they need to resort to **private stuff or
> directly interact with drivers**.

即：Windows 的公开 BLE API 不足以实现 MTA，小米自己也只能靠私有驱动级方案。

## 4. 结论与可选路径

| 路径 | 可行性 | 说明 |
|---|---|---|
| 改 BLE 广播让电脑搜到 | ❌ **不可能** | 电脑不扫 BLE |
| 实现 PC 那套私有 manufacturer 包 | ❌ | 私有栈 + 签名权限 |
| 用 `com.xiaomi.continuity` 框架 | ❌ | 需系统签名，类原生无此服务 |
| 完整逆向 Lyra/netbus 的 mDNS 宣告 | ⚠️ 难 | **无任何公开实现**；AppData protobuf 只解出一部分；还需绕过账号/HyperOS 版本校验与单播校验 |
| 以 MTA 协议在**PC 端**做接收器 | ⚠️ 中 | 需要 Windows 私有 BLE 路径（mtapy 在 macOS 上做过类似尝试）|
| **本项目内自建局域网通道**（推荐）| ✅ 高 | 不依赖小米协议，见下 |

### 推荐方向：让本 App 自己成为「多设备互传中心」

用户诉求是「**手机上只装这一个软件，就能跟多设备互传**」——
这不需要依赖小米电脑，因为我们已经有完整的传输能力（HTTP + WS + TLS 都在）。

可做的：
1. **接收模式暴露一个局域网 HTTP 上传页**：电脑浏览器打开
   `http://<手机IP>:<port>` 直接拖拽传文件，手机端收到后走现有的落盘 + 通知（P121/P122 复用）。
2. **手机当热点**：无路由器时手机开热点，电脑连上后访问同一地址。
3. 这样就覆盖了 **Windows / macOS / Linux / 任何有浏览器的设备**，
   比适配小米一家更通用，且**零新增第三方依赖**（符合开发准则）。

---

## 5. 如果仍要坚持打通小米电脑

需要满足以下**全部**条件，且不保证成功：

- [ ] 用户提供电脑端抓包（Wireshark，过滤 `udp port 5353`），取完整 `_lyra-mdns` 握手
- [ ] 解出 `AppData` 的 protobuf schema（含 framing 字节）
- [ ] 找到并复现单播校验流程
- [ ] 绕过「同账号 + HyperOS 版本 + 设备互联开关」校验（或确认不需要）
- [ ] 在手机上以**非系统 App** 身份完成上述宣告（受权限限制，很可能做不到）

**预计投入大、成功率低**，建议先做上面的「自建局域网通道」拿到可用体验。

---

## 6. ★重大发现：Windows **自带**的互传是可用的公开协议★

用户问「Windows 电脑自带的有互传服务吗」——**有，而且这次是公开协议、有开源实现。**

### Windows 自带「就近共享」（Nearby Sharing / 隔空投送）

- Windows 10/11 **内置**，无需装任何东西；设置 → 系统 → 体验共享 → 就近共享。
- 它跑的是 **`MS-CDP`（Connected Devices Platform Protocol v3）** + NearShare，
  也就是微软的 **Project Rome**。
- **微软把它作为开放规范公开了**：
  <https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cdp/>
  （有正式 PDF/DOCX 规范，含许可声明：允许据此开发实现）

### 关键：已有**完整的 Android 开源实现**

<https://github.com/nearby-sharing/android>（GPL-3.0，`NearShare`）
—— 这是"能让 Android 和 Windows 自带就近共享互通"的现成实现。

从它的源码（`/tmp/ns`，已 clone 验证）提取到的**具体协议参数**：

```csharp
// lib/ShortDev.Microsoft.ConnectedDevices/Constants.cs
public const int UdpPort = 5050;          // 局域网发现（UDP 广播）
public const int TcpPort = 5040;          // 数据连接
public const byte ProtocolVersion = 3;
public const ushort Signature = 0x3030;

// BLE 信标（近距离发现）
public const int BLeBeaconManufacturerId = /* Microsoft */ 0x6;
public const byte BLeBeaconVersion = 0x1;
public const int BLeBeaconDeviceNameMaxByteLength = 17;

// RFCOMM（蓝牙串口）
public const string RfcommServiceId  = "c7f94713-891e-496a-a0e7-983a0946126e";
public const string RfcommServiceName = "CDP Proximal Transport";
```

传输类型（`CdpTransportType`）：`Udp=1, Tcp=2, Cloud=3, Ble=4, Rfcomm=5, WifiDirect=6`
—— **一份协议，多种承载**。

BLE 信标布局（`BLeBeacon.TryParse`）：

| 偏移 | 字段 |
|---|---|
| 0 | ScenarioType = Bluetooth |
| 1 | DeviceType |
| 2 | version(高3位) + flags(低5位)，version 必须 = 1 |
| 3 | ExtendedDeviceStatus |
| 4..9 | MAC 地址（6 字节）|
| 10.. | 设备名（UTF-8，最多 17 字节）|

> 注：`flags` 必须为 `Public`，否则解析失败。

### 对项目的意义：**换一条路，反而更好走**

| | 小米电脑互联 | **Windows 就近共享（MS-CDP）** |
|---|---|---|
| 协议公开 | ❌ 私有 Lyra/netbus | ✅ **微软开放规范** |
| 有开源实现 | ❌ 无 | ✅ **nearby-sharing/android** |
| 发现方式 | mDNS（私有 TXT）| **UDP 5050 广播 / BLE / RFCOMM** |
| 需要账号 | 小米账号 | **不需要** |
| 需要系统权限 | 签名级 continuity | **不需要** |
| 系统要求 | HyperOS 3/4 + 设备互联开关 | **Win10/11 自带** |
| 电脑要装软件 | ✅ 要装「小米互联服务」| ❌ **不用装，系统自带** |

**结论：如果目标是「手机 ↔ Windows 电脑互传」，
适配 MS-CDP 远比逆向小米私有协议现实** ——
有公开规范、有可参考的开源实现、不需要账号、不需要系统权限、
而且电脑端**不用装任何软件**（正好符合用户"不想装一堆东西"的诉求）。

### 待确认（动手前必须先验证）

1. **[需用户配合]** 目标电脑是 Win10 还是 Win11？「就近共享」是否已开启、
   网络是否为「专用网络」（公用网络下 Windows 会关闭就近共享）。
2. 本机是 PixelOS 类原生 —— 需要确认 Android 侧能否在**非系统 App** 下
   完成 UDP 5050 广播收发与 BLE 信标（nearby-sharing 是普通 App，应该可行）。
3. MS-CDP 的**配对/加密**流程（`HMacSize=32`、`IVSize=16`）需按规范实现。
4. 传输层是**自己写**（UDP/TCP + BLE）还是只做其中一种。
   `Udp+Tcp` 可能就够（不依赖蓝牙），实现量更小。
