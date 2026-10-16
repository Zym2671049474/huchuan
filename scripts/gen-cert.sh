#!/system/bin/sh
# 生成自签名 TLS 证书（app/res/raw/huchuan.p12）
#
# ★这个文件是「公开凭据」，不是私钥★
#
# 为什么可以入库（见 P023 / Tls.java）：
#   互传联盟的传输层是 **TLS，但证书内容不被校验** ——
#   对端用 FakeTrustManager + hostnameVerifier{true}（CatShare 也是这么写的）。
#   所以这张证书只起"让 TLS 握手能建立"的作用，**没有任何保密价值**：
#     · 口令写在 app/src/com/huchuan/net/Tls.java 里（公开）
#     · 它不用于签名 APK，与 $HOME/.huchuan-keystore 完全是两回事
#
# 那为什么还要这个脚本？
#   p12 是个**不可读的二进制 blob**，入库后没人知道它怎么来的、怎么换。
#   有了本脚本，证书可以随时重新生成/轮换，项目不依赖一个来历不明的文件。
#
# 用法:
#   ./scripts/gen-cert.sh              # 仅在缺失时生成
#   ./scripts/gen-cert.sh --force      # 强制重新生成（会改变证书指纹）
#
# ⚠️ 重新生成会改变证书指纹。因为对端**不校验**证书，正常不影响互传；
#    但若某个实现做了指纹绑定，就需要同步。改完务必实传验证一次。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/res/raw/huchuan.p12"

# 与 Tls.java 中的常量保持一致
PASS="huchuan"
ALIAS="huchuan"
DNAME="CN=huchuan, OU=HuChuan, O=HuChuan, L=-, ST=-, C=CN"
VALIDITY=10950   # 30 年（原证书 2026-2056）

EXT=/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools
JAVA_HOME="$(ls -d "$EXT"/lib/jvm/*/ 2>/dev/null | head -1)"
JAVA_HOME="${JAVA_HOME%/}"
if [ ! -x "$JAVA_HOME/bin/keytool" ]; then
  echo "❌ 找不到 keytool（$JAVA_HOME/bin/keytool）" >&2
  echo "   请确认 android-buildtools 扩展已安装并激活。" >&2
  exit 1
fi

if [ -f "$OUT" ] && [ "$1" != "--force" ]; then
  echo "已存在 $OUT"
  echo "（要重新生成请加 --force；注意会改变证书指纹）"
  # 顺手把当前证书指纹打出来，便于和文档核对
  # 注意：sed 里不要用 \s（toybox sed 不支持，会报 trailing backslash），用 [[:space:]]
  "$JAVA_HOME/bin/keytool" -list -v -keystore "$OUT" -storetype PKCS12 \
    -storepass "$PASS" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*/SHA256: /p' || true
  exit 0
fi

echo "生成自签名证书 → $OUT"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
"$JAVA_HOME/bin/keytool" -genkeypair \
  -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg EC -groupname secp256r1 \
  -sigalg SHA384withECDSA \
  -validity "$VALIDITY" \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "$DNAME"

echo
echo "✅ 已生成。当前指纹："
"$JAVA_HOME/bin/keytool" -list -v -keystore "$OUT" -storetype PKCS12 -storepass "$PASS" \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*/SHA256: /p'
echo
echo "下一步：确认 Tls.java 里的 PASS / ALIAS 与上面一致，然后 ./build.sh"
echo "最后务必真机实传一次（证书不校验，正常应无影响）。"
