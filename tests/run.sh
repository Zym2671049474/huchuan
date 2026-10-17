#!/system/bin/sh
# 纯 JVM 单测：不需要设备、不需要 Android SDK
set -e
cd "$(dirname "$0")"
JAVA_HOME=$(ls -d /data/user/0/app.dsh.mobile/files/engine/extensions/*/lib/jvm/*/ 2>/dev/null | head -1)
[ -n "$JAVA_HOME" ] || { echo "❌ 找不到 JDK"; exit 1; }
JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
SRC=../app/src
FAIL=0

rm -rf out; mkdir -p out
echo "── 编译 JSON shim（覆盖 android.jar 的 stub）"
"$JAVAC" -nowarn -d out org/json/*.java

echo "── 编译被测类 + 测试"
"$JAVAC" -nowarn -cp out -d out \
    $SRC/com/huchuan/model/WsMessage.java \
    $SRC/com/huchuan/core/BleSecurity.java \
    $SRC/com/huchuan/core/BleUtils.java \
    $SRC/com/huchuan/cdp/CdpHeader.java \
    $SRC/com/huchuan/cdp/MsCdpDiscovery.java \
    jvm/*.java

for T in WsProtoTest CdpHeaderTest; do
    echo "── 运行 $T"
    "$JAVA" -cp out "$T" || FAIL=1
done

echo
[ $FAIL -eq 0 ] && echo "✅ 全部测试通过" || echo "❌ 有测试失败"
exit $FAIL
