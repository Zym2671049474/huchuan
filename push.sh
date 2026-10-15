#!/system/bin/sh
# 推送到 GitHub。用法:
#   GH_TOKEN=ghp_xxx ./push.sh                 # 首次推送（自动建私有仓库）
#   GH_TOKEN=ghp_xxx ./push.sh --update        # 后续更新
set -e
REPO_NAME="${GH_REPO:-huchuan}"
USER_LOGIN="${GH_USER:?请设置 GH_USER=你的GitHub用户名}"
TOKEN="${GH_TOKEN:?请设置 GH_TOKEN=你的Personal Access Token}"

if [ ! -d .git ]; then echo "❌ 当前目录不是 git 仓库"; exit 1; fi

if [ "$1" != "--update" ]; then
  echo "[1/3] 创建私有仓库 $USER_LOGIN/$REPO_NAME"
  curl -sS -X POST https://api.github.com/user/repos \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -d "{\"name\":\"$REPO_NAME\",\"private\":true,\"description\":\"互传 - 轻量跨设备文件互传 (纯 Java)\"}" \
    | grep -o '"full_name"[^,]*' || true
fi

echo "[2/3] 配置远程"
git remote remove origin 2>/dev/null || true
git remote add origin "https://$USER_LOGIN:$TOKEN@github.com/$USER_LOGIN/$REPO_NAME.git"

echo "[3/3] 推送"
git branch -M main 2>/dev/null || true
git push -u origin main
echo "✅ 已推送: https://github.com/$USER_LOGIN/$REPO_NAME"
