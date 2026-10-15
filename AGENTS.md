# AGENTS.md — 给接手本项目的 AI agent 的说明书

> **先读这个文件，再动手。** 读完后你应该能在 5 分钟内知道项目在哪、要做什么、怎么验证。

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 项目 | 互传（HuChuan）—— 轻量跨设备文件互传 |
| 目标设备 | PixelOS / 类原生 Android（需与小米 HyperOS 互通）|
| 语言 | **纯 Java**（本机无 Kotlin 编译器）|
| 构建 | `./build.sh`（aapt2 + javac + d8 + zipalign + apksigner）|
| 测试 | `./tests/run.sh`（**纯 JVM，不需要设备**）|
| 包名 | `com.huchuan` |
| 当前进度 | 见 [docs/进度.md](docs/进度.md) |
| **不要做** | 引入 Gradle / Kotlin / Compose / 任何第三方库 |

## 1. 必读文档（按顺序）

1. **`docs/进度.md`** — 现在做到哪了、下一步是什么 ← **最先读**
2. **`docs/协议规格.md`** — 协议全貌（UUID、数据模型、加密、WS 帧格式）
3. **`docs/踩坑日志.md`** — 已踩过的坑，**每一条都别再踩**
4. **`docs/双机测试指南.md`** — 需要两台设备测什么、怎么排查
5. **`docs/操作规范.md`** — 代码/提交/构建规范

## 2. 黄金法则

1. **动手前先读 `docs/进度.md`** —— 避免重复劳动
2. **做完必须更新 `docs/进度.md`** —— 这是多设备协作的唯一真相源
3. **踩到新坑立刻记到 `docs/踩坑日志.md`**（编号递增）
4. **改完必须跑 `./build.sh` 确认能编译通过**，能单测的加 `./tests/run.sh`
5. **不要引入任何第三方依赖** —— 本机无法解析 Maven 依赖，只能用手写代码 + Android 框架
6. **能用纯 JVM 验证的，就不要依赖设备** —— 见 `tests/README.md`

## 3. 构建

```sh
cd <repo>
./build.sh              # 产出 build/huchuan.apk
./build.sh install      # 构建 + 安装到本机
./tests/run.sh          # 纯 JVM 单测（协议/加密，不需要设备）
```

构建脚本会自动定位 JDK / aapt2 / d8，**无需手工设 PATH**。

## 4. 验证

**单机可验证的**（优先做这个，反馈快）：
```sh
./tests/run.sh
# 真机 smoke test：不用点屏幕，看日志即可
am start -n com.huchuan/.MainActivity
logcat -s HuChuan HuChuan.Sender HuChuan.Receiver HuChuan.P2p HuChuan.GattServer HuChuan.GattClient
am startservice -n com.huchuan/.ReceiverService     # 验证 BLE 广播能起来
```

**双机验证**：见 `docs/双机测试指南.md`（需要用户配合，agent 做不了）

## 5. 提交规范

```
<type>: <简述>

type ∈ feat | fix | docs | build | refactor | test | chore
```

**每次提交必须同时更新 `docs/进度.md`。**

## 6. 环境事实（已验证，别重新探索）

- 本机：Android，bionic libc，**无** python/gcc/apt（除非装扩展）
- `$HOME` = `/data/user/0/app.dsh.mobile/files/dsh-home`
- JDK 21：`$HOME/../engine/extensions/android-buildtools/lib/jvm/java-21-openjdk/bin/`
- `aapt2`/`d8`/`zipalign`/`apksigner`：`.../extensions/android-buildtools/bin/`
- `$HOME/android.jar` = **仅 javac classpath**（无资源表）
- `/system/framework/framework-res.apk` = **aapt2 用的资源表**
- 签名密钥：见 `docs/操作规范.md`

## 7. 代码结构

```
app/src/com/huchuan/
  MainActivity.java        主界面（选文件 / 开始接收）
  SenderService.java       发送流程编排（前台服务）
  ReceiverService.java     接收流程编排（前台服务）
  core/
    BleUtils.java          4 个 BLE UUID + 会话 ID
    BleSecurity.java       ECDH P-256 + AES/CTR
    P2pManager.java        WiFi Direct 建组/加入/移除
    GattServer.java        GATT 服务端 + BLE 广播（接收方）
    GattClient.java        BLE 扫描 + GATT 客户端（发送方）
    Perms.java             运行时权限
  net/
    MiniWebSocket.java     手写 RFC 6455（无依赖）
    Transfer.java          HTTP 文件服务 + WS 协商
  model/                   DeviceInfo / P2pInfo / WsMessage
tests/                     纯 JVM 单测（自带 org.json shim）
docs/                      协议/进度/踩坑/测试/双机指南
```

## 8. 写代码时的硬约束

| 约束 | 原因 |
|---|---|
| **不能用 lambda / 方法引用** | 严格 `android.jar` 缺 `LambdaMetafactory`（P011），只能写匿名内部类 |
| **不能用 androidx** | 无依赖仓库；用原生 `Activity.checkSelfPermission` |
| **不能用 `android.util.Base64`** | 它是 stub，纯 JVM 测试会 `Stub!`（P019）；改用 `java.util.Base64` |
| **BLE 读写必须支持 offset 分块** | 默认 ATT MTU 只有 23 字节（P018）|
| **P2P 等状态就绪，别等广播次数** | `CONNECTION_CHANGED` 首个是中间态（P016）|
| **Service 改 UI 用 `MainActivity.postLog`** | 跨线程；内部已切主线程 |

## 9. 推送（重要）

`github.com:443` **被墙**，`git push` 会挂起。必须用 Git Data API：

```sh
cd <repo>
git add -A && git commit -m "..."          # 先在本地提交
GH_TOKEN=<你的PAT> GH_REPO=Zym2671049474/huchuan node scripts/api-push.mjs
```

详见 `docs/推送指南.md`。
