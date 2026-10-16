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

# aapt2 / zipalign / apksigner 是本机构建的二进制，必须能找到
# engine/lib 下的 .so（apksigner 是 java 程序，缺 libandroid-shmem 会直接退出）
ENGINE_LIB=/data/user/0/app.dsh.mobile/files/engine/lib
PY_LIB=/data/user/0/app.dsh.mobile/files/engine/extensions/python/lib
export LD_LIBRARY_PATH="$ENGINE_LIB:$EXT/lib:$PY_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# 注意：必须用 = 直接赋值，不要在前面再写一次 export（会覆盖掉上面这行）
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
# 注意：本机 zip 不可用（缺 libbz2.so.1.0），用 python 打包含 classes.dex 的 apk
python3 - "$OUT" <<'PYEOF'
import sys, zipfile, os
OUT = sys.argv[1]
zin  = zipfile.ZipFile(os.path.join(OUT, "base.apk"))
zout = zipfile.ZipFile(os.path.join(OUT, "packed.apk"), "w", zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
zout.writestr("classes.dex", open(os.path.join(OUT, "dex/classes.dex"), "rb").read())
zout.close()
PYEOF

echo "[5/6] 对齐"
"$BIN/zipalign" -f 4 "$OUT/packed.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
KS="${HUCHUAN_KS:-$HOME/.huchuan-keystore}"
# ★口令不再散落在命令行里★（P124）
#
# 原来 storepass/keypass 是硬编码的 "huchuan123"，且在两处各写一遍。
# 保留原默认值（本机既有密钥就是这个口令，改了会签不了名），
# 但改成**一处变量 + 可用环境变量覆盖**，便于以后换密钥/接 CI：
#     HUCHUAN_KS_PASS=xxx ./build.sh
KSPASS="${HUCHUAN_KS_PASS:-huchuan123}"
if [ ! -f "$KS" ]; then
  echo "  生成新密钥 $KS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias huchuan \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=HuChuan, OU=Dev, O=HuChuan, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BIN/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --ks-key-alias huchuan --out "$OUT/huchuan.apk" "$OUT/aligned.apk"
"$BIN/apksigner" verify "$OUT/huchuan.apk" >/dev/null && echo "  ✅ 签名验证通过"
echo "✅ 构建完成: $OUT/huchuan.apk ($(stat -c%s "$OUT/huchuan.apk") 字节)"

# ★收尾必须是显式 exit 0★（P124）
#
# 原来这里是：
#     [ "$1" = "install" ] && /system/bin/pm install -r "$OUT/huchuan.apk"
#
# 脚本开头有 `set -e`，而**不传参数时 `[ "$1" = "install" ]` 为假**，
# 整条 `&&` 表达式返回 1；它又恰好是脚本的**最后一条命令**，
# 于是 build.sh 的退出码变成 1 —— 明明构建成功、APK 也签好了，
# 任何 CI / 脚本判 `if ./build.sh` 都会当成**失败**。
#
# 修法：把 install 放进 if 里（不参与退出码），末尾显式 exit 0。
if [ "$1" = "install" ]; then
  /system/bin/pm install -r "$OUT/huchuan.apk"
fi
exit 0
