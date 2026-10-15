# 互传 (HuChuan)

轻量级跨设备文件互传应用。在 PixelOS / 类原生 ROM 上实现与小米（HyperOS/MIUI）设备互传。

> 协议参考 [CatShare](https://github.com/kmod-midori/CatShare)，**从零用纯 Java 重写**，追求小而精。

## 特点

- **极小**：当前 21 KB（对比 CatShare 5.9 MB）
- **纯 Java**：无 Kotlin、无 Compose、无第三方依赖
- **不需要 root / Shizuku**：协议中 MAC 是死字段（见 `docs/踩坑日志.md` P001）
- **可离线构建**：`./build.sh` 全自动，无需 Gradle / Android SDK

## 快速开始

```sh
git clone <repo> && cd huchuan
./build.sh            # 产出 build/huchuan.apk
./build.sh install    # 构建并安装到本机
```

## 文档

| 文件 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | **AI agent 接手必读** |
| [`docs/进度.md`](docs/进度.md) | 当前进度 ← **最先看** |
| [`docs/协议规格.md`](docs/协议规格.md) | 协议全貌（UUID/数据模型/加密/WS）|
| [`docs/踩坑日志.md`](docs/踩坑日志.md) | 已踩的 13 个坑 |
| [`docs/操作规范.md`](docs/操作规范.md) | 代码/提交/构建规范 |
| [`docs/测试日志.md`](docs/测试日志.md) | 测试记录 |

## 进度

| 里程碑 | 状态 |
|---|---|
| M0 骨架+文档+构建 | ✅ |
| M1 协议基础层 | ✅ |
| M2 WiFi Direct | ⬜ 下一步 |
| M3 BLE 发现 | ⬜ |
| M4 文件收发 | ⬜ |
| M5 UI | ⬜ |
| M6 小米真机联调 | ⬜ |
| M7 剪贴板/文本 | ⬜ |
