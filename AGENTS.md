# AGENTS.md — 给接手本项目的 AI agent 的说明书

> **先读这个文件，再动手。** 读完后你应该能在 5 分钟内知道项目在哪、要做什么、怎么验证。

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 项目 | 互传（HuChuan）—— 轻量跨设备文件互传 |
| 目标设备 | PixelOS / 类原生 Android（需与小米 HyperOS 互通）|
| 语言 | **纯 Java**（本机无 Kotlin 编译器）|
| 构建 | `./build.sh`（aapt2 + javac + d8 + zipalign + apksigner）|
| 包名 | `com.huchuan` |
| 当前进度 | 见 [docs/进度.md](docs/进度.md) |
| **不要做** | 引入 Gradle / Kotlin / Compose / 任何第三方库 |

## 1. 必读文档（按顺序）

1. **`docs/进度.md`** — 现在做到哪了、下一步是什么 ← **最先读**
2. **`docs/协议规格.md`** — 协议全貌（UUID、数据模型、加密、WS 帧格式）
3. **`docs/踩坑日志.md`** — 已踩过的坑，**每一条都别再踩**
4. **`docs/操作规范.md`** — 代码/提交/构建规范

## 2. 黄金法则

1. **动手前先读 `docs/进度.md`** —— 避免重复劳动
2. **做完必须更新 `docs/进度.md`** —— 这是多设备协作的唯一真相源
3. **踩到新坑立刻记到 `docs/踩坑日志.md`**（加在最上面，编号递增）
4. **改完必须跑 `./build.sh` 确认能编译通过**，再更新 `docs/测试日志.md`
5. **不要引入任何第三方依赖** —— 本机无法解析 Maven 依赖，只能用手写代码 + Android 框架

## 3. 构建

```sh
cd <repo>
./build.sh              # 产出 build/huchuan.apk
./build.sh install      # 构建 + 安装到本机
```

构建脚本会自动定位 JDK / aapt2 / d8，**无需手工设 PATH**。

## 4. 验证

```sh
./build.sh install
# 两台设备（或一台 + 一台小米）打开 app，互相发送测试文件
```

详细测试清单见 `docs/测试日志.md`。

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
