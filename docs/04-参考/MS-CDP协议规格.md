# MS-CDP（Windows 就近共享）协议规格 —— 摘要

> 来源：微软公开规范 <https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cdp/>
> 参考实现：<https://github.com/nearby-sharing/android>（GPL-3.0，`ShortDev.Microsoft.ConnectedDevices`）
> 已实测核对源码（clone 于 /tmp/ns），以下为**从源码抄出的确切格式**，非推测。

---

## 0. 全部常量

```csharp
public const int  UdpPort        = 5050;   // 局域网发现（UDP 广播）
public const int  TcpPort        = 5040;   // 数据连接
public const byte ProtocolVersion = 3;
public const ushort Signature     = 0x3030;   // '0','0'

public const int  BLeBeaconManufacturerId = 0x6;   // Microsoft
public const byte BLeBeaconVersion        = 0x1;
public const int  BLeBeaconDeviceNameMaxByteLength = 17;

public const string RfcommServiceId   = "c7f94713-891e-496a-a0e7-983a0946126e";
public const string RfcommServiceName = "CDP Proximal Transport";
```

传输类型：`Unknown=0, Udp=1, Tcp=2, Cloud=3, Ble=4, Rfcomm=5, WifiDirect=6`

---

## 1. ★字节序：大端（BigEndian）★

**所有 CDP 消息体都是大端。** 这是最容易踩的坑 ——
`CommonHeader.Parse` 用的是 `EndianReader.FromMemory(Endianness.BigEndian, ...)`。

> **例外**：`CommonHeader.SetReplyToId` 里写的 `RequestID` 用 **LittleEndian**
> （`BinaryPrimitives.WriteUInt64LittleEndian`），读回来也用小端。
> 这是规范里的不一致处，照抄即可。

---

## 2. CommonHeader（所有消息共有）

| 偏移 | 类型 | 字段 |
|---|---|---|
| 0 | u16 BE | `Signature` = `0x3030`（否则丢弃）|
| 2 | u16 BE | `MessageLength`（**整个消息长度，含签名和头**）|
| 4 | u8 | `Version`，必须 = **3** |
| 5 | u8 | `Type`（MessageType）|
| 6 | i16 BE | `Flags` |
| 8 | u32 BE | `SequenceNumber` |
| 12 | u64 BE | `RequestID` |
| 20 | u16 BE | `FragmentIndex`（从 0 起）|
| 22 | u16 BE | `FragmentCount`（≥1）|
| 24 | u64 BE | `SessionId`（无会话时 0）|
| 32 | u64 BE | `ChannelId`（SessionId 为 0 时也 0）|
| 40.. | 重复 | 附加头：`u8 type` + `u8 size` + `size` 字节值 |
| … | u8 + u8 | 结束标记：`AdditionalHeaderType.None(0)` + `0x00` |

头部固定 **42 字节**（40 + 2 结束标记），无附加头时。

`MessageLength` 必须包含 HMAC（见 §5）。
`PayloadSize = MessageLength - 头部实际大小`。

`MessageType`：`None=0, Discovery=1, Connect=2, Control=3, Session=4, Ack=5, ReliabilityResponse=6, Disconnect=7`

`SessionIdHostFlag = 0x80000000`（用于区分 host/client 会话位）

---

## 3. 发现（Discovery）—— 让电脑看见我们

### 3.1 PresenceRequest（广播找设备）

- 发到 **UDP 5050**，目标地址 `IPAddress.Broadcast`
- 载荷：
  ```
  DiscoveryHeader: u8 Type = PresenceRequest (0)
  ```
- 整个包 = CommonHeader(Type = Discovery) + 上面 1 字节

### 3.2 PresenceResponse（应答，让对方认识我们）

**单播**回给请求方 **UDP 5050**。★以下以**微软官方规范**为准★
(<https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-cdp/32463f09-066c-4f45-8b99-0d733c136a54>)：

| 字段 | 宽度 | 值 |
|---|---|---|
| CommonHeader | 42 | `Type` = Discovery(1) |
| `Message_Type` | 1 | = **1**（Discovery）← 注意：**载荷里又出现一次** |
| `Discovery_Type` | 1 | = **1**（Presence Response）|
| `Connection_Mode` | 2 | `0=None, 1=Proximal, 2=Legacy` |
| `Device_Type` | 2 | `8`=Android、`9`=Win10 桌面、`15`=Win 笔记本、`12`=Linux |
| `Device_Name_Length` | **2** | 设备名长度 |
| `Device_Name` | 变长 | 设备名字符串 |
| `DeviceIdSalt` | 4 | 随机盐 |
| `DeviceIdHash` | **4** | 加盐 SHA-256（**只取 4 字节**）|
| `PrincipalUserNameHash` | 4 | 账号邮箱的加盐 SHA-256 |
| `MacAddress` | 6 | 蓝牙 MAC，用于去重 |

> ⚠️ **两个必须注意的点**（官方规范 vs 开源实现有出入）：
>
> 1. **`DeviceIdHash` 官方规范写的是 4 字节**，而开源实现里是 32 字节
>    （`DeviceIdHash hash = default` 配 `SHA256` 的 32 字节结构体）。
>    以**官方为准写 4 字节**；若电脑不认，再试 32 字节。
> 2. 官方布局里有 **`PrincipalUserNameHash`(4) 和 `MacAddress`(6)**，
>    而开源实现的 `PresenceResponse` **没有这两个字段** ——
>    说明该实现可能只覆盖了部分版本，或依赖于对方宽松解析。
>    实现时**优先按官方规范发全字段**。

`DeviceIdHash` 算法：`SHA256(salt || 内部设备ID)`，取前 4 字节（官方口径）。

> 开源实现里写入用的字节序是 **LittleEndian**，且源码里有 `// ToDo: Wrong` 注释；
> 官方规范的字段表未标注字节序，按 CDP 惯例应为**大端**。
> 两种都要能试（做成可切换的常量）。

## 4. 字符串编码

**长度前缀 = 2 字节**（官方规范明确 `Device_Name_Length (2 bytes)`，
`PublicKeyXLength (2 bytes)` 等）。字符串本身是 UTF-8。

> 开源实现用的是 `ShortDev.IO` 的 `WriteWithLength`/`ReadStringWithLength`，
> 该包还有 `EncodeVarUInt16/32` 等变长编码，但**官方规范统一写 2 字节定长**，
> 所以按 **u16** 实现。

---

## 5. 分段与加密

- `MessageFragmenter.DefaultMessageFragmentSize` —— 分片大小常量
- 加密：`HMacSize = 32`、`IVSize = 16`
- `PayloadSize` 包含 HMAC → **握手完成后每个消息尾部有 32 字节 HMAC**

### 5.1 加解密细节（已从源码抄全）

`sharedSecret` 是 32+32+32 = 96 字节结构，切分如下：

```java
aesKey  = sharedSecret[0..16]     // 16 字节，AES 密钥（用于加密消息体）
ivKey   = sharedSecret[16..32]    // 16 字节，AES 密钥（仅用于派生 IV）
hmacKey = sharedSecret[32..64]    // 32 字节，HMAC-SHA256 密钥
```

**IV 不是随机的，而是由消息头算出来的**（这样双方无需传输 IV）：

```java
// raw 共 16 字节，全部大端
raw[0..8]   = SessionId     (u64 BE)
raw[8..12]  = SequenceNumber(u32 BE)
raw[12..14] = FragmentIndex (u16 BE)
raw[14..16] = FragmentCount (u16 BE)

IV = AES-CBC-Encrypt(key = ivKey, iv = 全零(16字节), data = raw, padding = None)
     // 结果 16 字节，正是 IV
```

**消息体加密**：`AES-CBC(key = aesKey, iv = 上面算出的 IV)`

**HMAC**：`HMACSHA256(key = hmacKey, data = <消息头 + 密文>)`，取 32 字节附在尾部。

> ★密钥交换发生在 Connect 握手阶段★（`Session/Connection/ConnectHandler.cs`），
> 用 ECDH 交换公钥（见官方规范的 Connection Response：
> `PublicKeyXLength(u16) + PublicKeyX + PublicKeyYLength(u16) + PublicKeyY`）。
> 我们项目里 **`core/BleSecurity.java` 已有 ECDH P-256 实现，可复用**。

> ⚠️ 注意：`GenerateIV` 用的是**同一个 ivKey 做 AES-CBC 加密全零 IV 的 raw**，
> 这不是标准 KDF，但是规范如此 —— 照抄即可。

---

## 6. 连接流程（TCP 5040）

```
Discovery(UDP 5050)  →  找到设备
      ↓
TCP 5040 连接
      ↓
Connect 消息（握手 / 密钥交换）
      ↓
Control / Session 消息（业务通道）
      ↓
NearShare 应用层：发文件
```

相关源码：`Session/Connection/ConnectHandler.cs`、
`Session/Connection/HostConnectHandler.cs`、`Session/Channels/`

---

## 7. BLE 信标（可选，近距离发现）

厂商 ID = **0x6（Microsoft）**，版本 = 1。载荷布局：

| 偏移 | 字段 |
|---|---|
| 0 | `ScenarioType` = Bluetooth |
| 1 | `DeviceType`（u8）|
| 2 | `version(高3位) << 5 \| flags(低5位)`，version 必须 = 1 |
| 3 | `ExtendedDeviceStatus`（u8）|
| 4..9 | MAC 地址（6 字节）|
| 10.. | 设备名（UTF-8，最多 17 字节）|

`flags` 必须 = `Public`；`deviceStatus` 常用
`RemoteSessionsNotHosted | NearShareAuthPolicyPermissive`。

---

## 8. 实现顺序建议

1. **先做 UDP 5050 发现**（§2 + §3）—— 最小可验证：
   只要电脑的「就近共享」列表里能出现本机，就证明方向对了。
   这一步**不需要加密、不需要 TCP**。
2. 再做 TCP 5040 连接与握手（§6，需补 §5 加密细节）。
3. 最后做 NearShare 文件收发。

> ★第 1 步是关键里程碑★：它是整条路上**唯一能快速证伪**的检查点。

---

## 9. ★已实测的环境前提（本机验证通过）★

在动手写代码前，先验证了最容易"整条路走不通"的前提：

```java
DatagramSocket s = new DatagramSocket(null);
s.setReuseAddress(true);
s.setBroadcast(true);
s.bind(new InetSocketAddress(5050));   // ✅ 成功，普通 App 权限即可
s.send(... 255.255.255.255:5050);      // ✅ 广播发送成功
```

**结论：Android 侧绑定 5050 + 广播都不需要特殊权限**
（`INTERNET` 就够，manifest 里已有）。

### 本机网卡与广播地址（实测）

| 接口 | IP | 广播地址 |
|---|---|---|
| `wlan0` | 172.20.122.108/17 | `172.20.127.255` |
| `wlan2` | 192.168.207.216/24 | `192.168.207.255` |

> ⚠️ **有两块 wlan**。向哪个网段广播决定了电脑能不能收到 ——
> 必须和电脑在同一网段。实现时要**遍历所有网卡的广播地址逐个发**，
> 不能只发 `255.255.255.255`（部分环境会被丢弃）。

### 因此的编码要求

1. 用 `NetworkInterface.getNetworkInterfaces()` 枚举，
   对每个 `InterfaceAddress.getBroadcast()` **逐个发送**。
2. 同时监听 5050 接收（Windows 会主动广播 PresenceRequest，
   我们要能应答 —— 这条路比我们主动找更可靠）。
3. 绑定用 `setReuseAddress(true)`，避免与系统其他程序冲突。


---

## 10. ★规范 vs 参考实现的差异（★已精确核对，勿再夸大★）

> ⚠️ **本节曾写错过一次**：初版声称开源实现"没有 Message_Type/Discovery_Type"，
> 那是**误读** —— 那两个字段由 `DiscoveryHeader` 单独写入，两边都有。
> 下面是**逐字段核对后**的准确差异。

### 两边完全一致的部分

```
(CommonHeader 42 字节，Type = Discovery)
DiscoveryHeader: Message_Type(u8)=1, Discovery_Type(u8)=1   ← 两边都有
Connection_Mode(u16), Device_Type(u16)
Device_Name_Length(u16) + Device_Name(UTF-8)
DeviceIdSalt(i32)
```

### ★唯一的真实差异：DeviceIdHash 长度 + 尾部字段★

| | 微软官方规范 | nearby-sharing 参考实现 |
|---|---|---|
| `DeviceIdHash` | **4 字节** | **32 字节**（`[InlineArray(32)]`）|
| `PrincipalUserNameHash`(4) | 有 | **不发** |
| `MacAddress`(6) | 有 | **不发** |

参考实现的完整写入（`PresenceResponse.Write`）到 `DeviceIdHash` 就结束了。

### 怎么选（`MsCdpDiscovery.PRESENCE_RESPONSE_LAYOUT`）

| 取值 | DeviceIdHash | 尾部 | 依据 | 载荷长度（名字 12 字节时）|
|---|---|---|---|---|
| `LAYOUT_OFFICIAL_SPEC` | 4 | 发(4+6) | 微软官方规范 | 38 |
| `LAYOUT_OPEN_SOURCE`（默认）| 32 | 不发 | 参考实现 | 56 |

**默认选 `LAYOUT_OPEN_SOURCE` 的理由**：这是**唯一有公开证据能与真实 Windows 互通**的组合
（该 App 已在 Play/F-Droid 发布，README 自称 "fully functional implementation of MS-CDP"）。
但它**偏离官方规范**，所以这属于**待实测确认的假设，不是已证事实**。

> ★诚实说明★：我们**没有** Windows 端抓包或截图来证明哪一种对。
> 两种都可能对（Windows 也许容忍两种长度）。
> 若电脑列表里不出现本机 → **把 `PRESENCE_RESPONSE_LAYOUT` 改成 `LAYOUT_OFFICIAL_SPEC` 再试**。
> 两种都失败则说明问题在别处（网段/防火墙/开关），不要在这一个常量上反复折腾。

> 📌 后续应做的：拿到 Windows 端 Wireshark 抓包（`udp port 5050`），
> 直接看电脑自己发的 PresenceResponse 有多长，以实测取代推断。

### 附：另一处字节序分歧

参考实现的 `DeviceIdSalt` 用 `EndianWriter.Create(Endianness.LittleEndian, ...)`
（它自己的 `// ToDo: Wrong` 注释也承认可疑），而消息体其余部分是大端。
我们按**大端**实现，并保留 `REPLY_REQUEST_ID_LITTLE_ENDIAN` 常量备用。
