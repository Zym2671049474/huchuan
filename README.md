# 互传 (HuChuan)

轻量级跨设备文件互传应用。在 PixelOS / 类原生 ROM 上实现与小米（HyperOS/MIUI）设备互传。

> 协议参考 [CatShare](https://github.com/kmod-midori/CatShare)，**从零用纯 Java 重写**，追求小而精。

## 特点

- **极小**：45 KB（对比 CatShare 5.9 MB）
- **纯 Java**：无 Kotlin、无 Compose、无第三方依赖
- **不需要 root / Shizuku**：协议中 MAC 是死字段（见 `docs/踩坑日志.md` P001）
- **可离线构建**：`./build.sh` 全自动，无需 Gradle / Android SDK
- **可脱离设备测试**：`./tests/run.sh` 纯 JVM 跑协议断言

## 快速开始

```sh
git clone <repo> && cd huchuan
./build.sh            # 产出 build/huchuan.apk
./build.sh install    # 构建并安装到本机
./tests/run.sh        # 纯 JVM 单测（不需要设备）
```

## 怎么用

**发送**：打开 app → 「选择文件发送」→ 选文件
（或从其他 app「分享」到互传）

**接收**：打开 app → 「开始接收」→ 等对方发送

文件保存在 `Download/互传/`

## 文档

| 文件 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | **AI agent 接手必读** |
| [`docs/进度.md`](docs/进度.md) | 当前进度 ← **最先看** |
| [`docs/双机测试指南.md`](docs/双机测试指南.md) | **两台设备怎么测** |
| [`docs/协议规格.md`](docs/协议规格.md) | 协议全貌（UUID/数据模型/加密/WS）|
| [`docs/踩坑日志.md`](docs/踩坑日志.md) | 已踩的 20 个坑 |
| [`docs/操作规范.md`](docs/操作规范.md) | 代码/提交/构建规范 |
| [`docs/测试日志.md`](docs/测试日志.md) | 测试记录 |

## 进度

| 里程碑 | 状态 |
|---|---|
| M0 骨架+文档+构建 | ✅ |
| M1 协议基础层 | ✅ 真机验证 |
| M2 WiFi Direct | ✅ 真机验证 |
| M3 BLE 发现 | ✅ 单机验证 |
| M4 文件收发 | ✅ 单机验证 |
| M5 UI | 🔶 基础版 |
| M6 双机/小米真机联调 | ⬜ **待测试** |
| M7 剪贴板/文本 | ⬜ |
