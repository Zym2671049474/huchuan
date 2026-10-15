#!/system/bin/sh
# 互传 构建脚本 —— 纯 Java，无需 Gradle/Kotlin
# 用法: ./build.sh [install]
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
EXT=/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools
BIN="$EXT/bin"
JAVA_HOME="$(ls -d "$EXT"/lib/jvm/*/ 2>/dev/null | head -1)"
JAVA_HOME="${JAVA_HOME%/}"
[ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 JDK"; exit 1; }
FW=/system/framework/framework-res.apk
CPJAR="$HOME/android.jar"
OUT="$ROOT/build"; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "[1/6] 编译资源 (aapt2)"
"$BIN/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
"$BIN/aapt2" link -o "$OUT/base.apk" -I "$FW" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  "$OUT/res.zip" --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35

echo "[2/6] 编译 Java"
SRCS=$(find "$ROOT/app/src" "$OUT/gen" -name '*.java' 2>/dev/null)
if ! "$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn \
  -bootclasspath "$CPJAR" -cp "$CPJAR" -d "$OUT/classes" $SRCS >"$OUT/javac.log" 2>&1; then
  grep -v "^Note:" "$OUT/javac.log" || true
  echo "❌ javac 编译失败，构建中止" >&2
  exit 1
fi
[ -d "$OUT/classes/com" ] || { echo "❌ javac 未产出任何 class" >&2; exit 1; }

echo "[3/6] 转 dex (d8)"
"$JAVA_HOME/bin/java" -cp "$EXT/share/java/d8.jar" com.android.tools.r8.D8 \
  --min-api 29 --release --lib "$CPJAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[4/6] 打包"
cp "$OUT/dex/classes.dex" "$OUT/base.apk.dir" 2>/dev/null || true
(cd "$OUT" && zip -q -j base.apk dex/classes.dex)

echo "[5/6] 对齐"
"$BIN/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
KS="${HUCHUAN_KS:-$HOME/.huchuan-keystore}"
if [ ! -f "$KS" ]; then
  echo "  生成新密钥 $KS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias huchuan \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass huchuan123 -keypass huchuan123 \
    -dname "CN=HuChuan, OU=Dev, O=HuChuan, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BIN/apksigner" sign --ks "$KS" --ks-pass pass:huchuan123 --key-pass pass:huchuan123 \
  --ks-key-alias huchuan --out "$OUT/huchuan.apk" "$OUT/aligned.apk"
"$BIN/apksigner" verify "$OUT/huchuan.apk" >/dev/null && echo "  ✅ 签名验证通过"
echo "✅ 构建完成: $OUT/huchuan.apk ($(stat -c%s "$OUT/huchuan.apk") 字节)"
[ "$1" = "install" ] && /system/bin/pm install -r "$OUT/huchuan.apk"
