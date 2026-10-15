# 单元测试（纯 JVM，无需设备）

```sh
./tests/run.sh
```

## 为什么能脱离设备跑

`android.jar` 里全是 **stub**（调用即 `RuntimeException: Stub!`），`org.json` 也是。
本目录自带一份极简 `org/json/{JSONObject,JSONArray,JSONException}.java`，
**放在 classpath 最前面**覆盖 stub，于是 `WsMessage` / `BleSecurity` 可直接在 JVM 上测。

配套要求：被测代码不能用 `android.util.Base64`（已是 stub），
必须用 `java.util.Base64`（API 26+ 原生支持）—— 见踩坑 P019。

## 覆盖

| 文件 | 内容 |
|---|---|
| `jvm/WsProtoTest.java` | WS 帧格式 10 项断言 + ECDH 加密往返 |

## 加新测试

1. 把被测类加进 `run.sh` 的编译列表（**只能加不依赖 Android API 的类**）
2. 写断言类放到 `jvm/`
3. 在 `run.sh` 末尾加一行 `"$JAVA" -cp out YourTest`
