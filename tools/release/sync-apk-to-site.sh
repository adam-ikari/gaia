#!/usr/bin/env bash
# 把私有应用仓库的 release APK 同步到公开站点仓库的同名 release。
#
# ## 为什么需要这个脚本(而不是只靠 CI)
#
# 应用仓库 `adam-ikari/hwkbd_ime` 是私有的,那里的 release 资产**匿名下不到**
# (实测 `releases/latest/download/app-release.apk` 返回 404)。站点访客要能下载,
# 包就必须同时存在于公开的 `adam-ikari/gaia`。
#
# CI 里有一个等价的 job(`release.yml` 的 `publish-site`),但它要 `WEB_RELEASE_TOKEN`:
# 跨仓库写权限只能来自 PAT,内置的 `GITHUB_TOKEN` 只对本仓库有效。
# **这个 token 目前没有配**,所以那个 job 一直 skipped。
#
# ## 两条路,各有用处
#
# 1. **本脚本**(现在这条):谁手上有 `adam-ikari/gaia` 的写权限,发完版跑一次。
#    不需要任何 secret,不会过期。发版流程里的一步,不是配置。
# 2. **CI 的 `publish-site` job**:配了 `WEB_RELEASE_TOKEN` 之后自动跑,
#    不用记着跑这个脚本。适合不想每次都手动的情况。
#
# 两条不冲突:脚本幂等,重复跑只是覆盖同名资产。
#
# 用法:
#   tools/release/sync-apk-to-site.sh v0.4.3
#   tools/release/sync-apk-to-site.sh            # 不给 tag 就用当前 master 的最新 tag
set -euo pipefail

APP_REPO=adam-ikari/hwkbd_ime
SITE_REPO=adam-ikari/gaia

TAG="${1:-}"
if [ -z "$TAG" ]; then
  TAG=$(gh release list -R "$APP_REPO" --limit 1 --json tagName -q '.[0].tagName')
fi
if [ -z "$TAG" ]; then
  echo "::error::没给 tag,且 $APP_REPO 上查不到 release" >&2
  exit 1
fi

echo "同步 $TAG:$APP_REPO → $SITE_REPO"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 从私有仓库取包(这一步需要 gh 有该仓库的读权限 —— 有 token 或有账号权限都行)
gh release download "$TAG" -R "$APP_REPO" \
  -p "app-release.apk" -p "app-debug.apk" --clobber --dir "$WORK" >/dev/null

for f in app-release.apk app-debug.apk; do
  if [ ! -f "$WORK/$f" ]; then
    echo "::error::$TAG 里没有 $f,发版是不是没跑完?" >&2
    exit 1
  fi
done

# 幂等:同名 release 已存在就只覆盖资产,不重建(否则会把已有的说明文字冲掉)
if gh release view "$TAG" -R "$SITE_REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$WORK/app-release.apk" "$WORK/app-debug.apk" \
    -R "$SITE_REPO" --clobber >/dev/null
  echo "已覆盖 $SITE_REPO @ $TAG 的同名资产"
else
  gh release create "$TAG" -R "$SITE_REPO" \
    --title "Gaia $TAG" \
    --notes "盖亚输入法 $TAG。由 $APP_REPO 的发版流水线手动同步。" \
    "$WORK/app-release.apk" "$WORK/app-debug.apk" >/dev/null
  echo "已在 $SITE_REPO 建 $TAG"
fi

# **验一次匿名可下**。这一步不是走过场:2026-10-02 之前站点一直指向 v0.4.1,
# 而 CI 那个 job 是 skipped 状态、退出码 0 —— 流水线全绿,没人发现链接是旧的。
# 同步完不验,等于把同一个坑留到下一次。
url="https://github.com/$SITE_REPO/releases/latest/download/app-release.apk"
code=$(curl -sIL --max-time 120 -o /dev/null -w '%{http_code}' "$url")
if [ "$code" != "200" ]; then
  echo "::error::匿名下载返回 $code,不是 200 —— 同步没生效,别当成成功" >&2
  exit 1
fi
echo "✓ 匿名下载 200:$url"
echo "  (latest 落在 $(gh release list -R "$SITE_REPO" --limit 1 --json tagName -q '.[0].tagName'))"

# ---- 让站点把新包取走 ------------------------------------------------------------
#
# **release 资产与 Pages 上的那份是两个东西**(2026-10-02 差点只做一半):
#   - 上面同步的是 gaia 仓库的 **release 资产** —— `releases/latest/download/…` 那条路;
#   - 站点按钮指的 `/media/gaia.apk` 是 **Pages 的构建产物**,由站点仓库的
#     `Deploy site` 从 release 拉下来放进 dist。
# Pages 产物**只在站点仓库被 push/手动触发时才刷新**,同步 release 不会通知它。
# 所以这里显式触发一次,否则站点继续发上一次的包,而上面那步还验过 200 —— 验的
# 还是旧包。
echo "触发站点重新部署(把新包取进 Pages)…"
gh workflow run deploy.yml --repo "$SITE_REPO" || {
  echo "::error::触发站点部署失败 —— release 资产是新的,但站点页面仍是上一次的包" >&2
  exit 1
}
echo "  已触发 $SITE_REPO 的 Deploy site。等它跑完后:"
echo "  https://adam-ikari.github.io/gaia/media/gaia.apk 才是 $TAG"
