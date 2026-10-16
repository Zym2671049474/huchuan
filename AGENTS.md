# AGENTS.md — 给接手本项目的 AI agent 的说明书

> **先读这个文件，再动手。** 读完后你应该能在 5 分钟内知道项目在哪、要做什么、怎么验证。

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 项目 | 互传（HuChuan）—— 与小米 HyperOS「小米互传」互通的文件传输 App |
| 语言 | **纯 Java**（无 Kotlin，无 Gradle，**零第三方依赖**）|
| 构建 | `./build.sh`（aapt2 + javac + d8 + zipalign + apksigner）|
| 测试 | `./tests/run.sh`（**纯 JVM，不需要设备**，当前 10/0）|
| 包名 | `com.huchuan` |
| 版本 | `v0.2.0`（见 [版本号准则](docs/01-规范/版本号准则.md)）|
| 当前进度 | 见 [docs/03-进度/进度.md](docs/03-进度/进度.md) |
| **不要做** | 引入 Gradle / Kotlin / androidx / lambda / 任何第三方库 |

## 1. 必读文档（按顺序）

1. **[`docs/03-进度/接手指南-当前状态.md`](docs/03-进度/接手指南-当前状态.md)** — **5 分钟上手**，含硬约束与验证命令 ← **最先读**
2. **[`docs/03-进度/进度.md`](docs/03-进度/进度.md)** — 做到哪了、下一步是什么
3. **[`docs/01-规范/开发准则.md`](docs/01-规范/开发准则.md)** — 什么能做、什么不能做（**最高约束**）
4. **[`docs/04-参考/协议规格.md`](docs/04-参考/协议规格.md)** — 协议全貌（UUID、数据模型、加密、TLS）
5. **[`docs/踩坑日志.md`](docs/踩坑日志.md)** — 日志索引（正文已拆分）
   - 现行：[`docs/02-日志/踩坑日志-P054-P125.md`](docs/02-日志/踩坑日志-P054-P125.md) ← **先看开头几条**
   - 归档：[`docs/99-归档/踩坑日志-改APK时期-P001-P053.md`](docs/99-归档/踩坑日志-改APK时期-P001-P053.md)

## 2. 黄金法则

1. **动手前先读 `docs/03-进度/进度.md`** —— 避免重复劳动
2. **做完必须更新 `docs/03-进度/进度.md`** —— 这是唯一真相源
3. **踩到新坑立刻记到 `docs/02-日志/踩坑日志-P054-P125.md`**（加在最上面，编号递增，**下一个是 P126**）
4. **改完必须 `./build.sh` 退出码 0**，并跑 `./tests/run.sh`（必须全绿）
5. **不要引入任何第三方依赖** —— 本机无法解析依赖，只能手写 + Android 框架
6. **不要用推测代替复现**（P027 是整段弯路的总根因）；关键改动要有**实测证据**

## 3. 构建与测试

```sh
cd ~/huchuan
./build.sh              # 产出 build/huchuan.apk（★退出码必须为 0★）
./build.sh install      # 构建 + 安装到本机
./tests/run.sh          # 纯 JVM 单测（协议/加密，不需要设备）
```

构建脚本自动定位 JDK / aapt2 / d8，**无需手工设 PATH**。

**动手前先跑一遍这两条确认环境是好的**：`./build.sh && ./tests/run.sh`

## 4. 验证

**单机可验证的**（优先，反馈快）：
```sh
./tests/run.sh
logcat -s HuChuan.Sender HuChuan.Receiver HuChuan.P2p HuChuan.GattServer HuChuan.GattClient HuChuan.Tile HuChuan.RecvState
```
> ⚠️ **不要用 `am` 模拟启动服务测功能**（P053 会制造假故障）。走真实流程。

**双机验证**：见 [`docs/04-参考/双机测试指南.md`](docs/04-参考/双机测试指南.md)（需要用户配合）

## 5. 提交规范

```
<type>: <简述>（P<编号>）

type ∈ feat | fix | docs | build | refactor | test | chore
```

**每次提交必须同时更新 `docs/03-进度/进度.md`。**
**每个提交都要能独立构建**（否则 `git bisect` 失效 —— P125 的教训）。

## 6. 环境事实（已验证，别重新探索）

- 本机：Android，bionic libc；`grep`/`pm`/`settings` 的 shell shim **是坏的** → 用 `rg`/`awk`/`sed`
- `$HOME` = `/data/user/0/app.dsh.mobile/files/dsh-home`
- `aapt2`/`d8`/`zipalign`/`apksigner`/JDK：`.../extensions/android-buildtools/{bin,lib/jvm}`
- `$HOME/android.jar` = **仅 javac classpath**（无资源表）
- `/system/framework/framework-res.apk` = **aapt2 用的资源表**
- **git 必须带 `-c safe.directory='*'`**（仓库属主是 root）
- 签名密钥：`$HOME/.huchuan-keystore`（仓库外，见 [开发准则](docs/01-规范/开发准则.md) §7）

## 7. 代码结构

```
app/src/com/huchuan/
  ReceiverService.java      接收侧主服务（广播 / 计时 / 落盘 / 通知）
  SenderService.java        发送侧主服务
  ShareActivity.java        系统分享入口（设备选择弹窗）
  ReceiverTile.java         快捷磁贴（开关接收）
  TileSettingsActivity.java 设置页（10 分钟 / 永久；权限申请也在这）
  FileContentProvider.java  自写的最小 FileProvider（替代 androidx）
  ReceiverActionReceiver.java  通知按钮动作
  core/
    BleUtils.java           4 个 BLE UUID + service data 构造
    BleSecurity.java        ECDH P-256 + AES/CTR
    P2pManager.java         WiFi Direct 建组/加入/移除
    GattServer.java         GATT 服务端 + BLE 广播（接收方）
    GattClient.java         BLE 扫描 + GATT 客户端（发送方）
    ReceiverState.java      接收开关状态（持久化 + TTL 计时）
    DeviceName.java         读系统设备名
    DeviceProfile.java      广播布局（照小米互传）
    RootMacReader.java      取真实接口 MAC
    Noti.java / NotiIds.java  通知渠道/分组/ID 集中管理
  net/
    MiniWebSocket.java      手写 RFC 6455（无依赖）
    Transfer.java           HTTP/WS 统一端口服务 + 协商
    Tls.java                TLS（含随包自签名证书）
  model/                    DeviceInfo / P2pInfo / WsMessage
  ui/                       DeviceKind / KnownDevices
tests/                      纯 JVM 单测（自带 org.json shim）
scripts/                    推送脚本 + 证书生成
docs/                       规范 / 日志 / 进度 / 参考 / 归档
```

## 8. 写代码时的硬约束

| 约束 | 原因 |
|---|---|
| **不能用 lambda / 方法引用** | 严格 `android.jar` 缺 `LambdaMetafactory`（P011），只能匿名内部类 |
| **不能用 androidx** | 无依赖仓库；用自写实现（如 `FileContentProvider`）|
| **不能用 `android.util.Base64`** | 它是 stub，纯 JVM 测试会 `Stub!`（P019）；改用 `java.util.Base64` |
| **BLE 读写必须支持 offset 分块** | 默认 ATT MTU 只有 23 字节（P018）|
| **P2P 等状态就绪，别等广播次数** | `CONNECTION_CHANGED` 首个是中间态（P016）|
| **广播 UUID 必须用 `0x01ff`** | 冒用小米私有 `0x1b1e` → Mi 15 搜不到（P106/P107）|
| **`/download` 必须在 `status(ok)` 之前** | 发送方收到 status 就拆 P2P 组（P080）|

## 9. 推送（重要）

`github.com:443` **被墙**，`git push` 会挂起。必须用 Git Data API：

```sh
cd ~/huchuan
git add -A && git commit -m "..."          # 先在本地提交
GH_TOKEN=<你的PAT> GH_REPO=Zym2671049474/huchuan node scripts/api-push-history.mjs
```

- **默认用 `api-push-history.mjs`**（保留提交历史）；
  `api-push.mjs` 会把历史压成一个提交，仅在同步"快照"时用。
- token 只走环境变量，**绝不写进文件或提交**；用完立即 revoke。

详见 [`docs/04-参考/推送指南.md`](docs/04-参考/推送指南.md)。
