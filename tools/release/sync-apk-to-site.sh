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
#
# 下载同样要重试:两个包合计 ~29MB,而 `gh release download` 走的是 release-assets
# 的直链,大文件会 `unexpected EOF`(实测连续两次)。
# 与上传同理:**release 包取不到是致命的**,debug 包取不到就降级(见下面那个循环的注释)。
dl_ok=0
for attempt in 1 2 3 4 5; do
  if gh release download "$TAG" -R "$APP_REPO" \
       -p "app-release.apk" -p "app-debug.apk" --clobber --dir "$WORK" >/dev/null 2>&1; then
    dl_ok=1; break
  fi
  echo "  下载第 $attempt 次失败,重试"
  sleep $((attempt * 5))
done
if [ "$dl_ok" != "1" ] && [ ! -f "$WORK/app-release.apk" ]; then
  echo "::error::从 $APP_REPO 下载 $TAG 连续 5 次失败,且连 app-release.apk 都没有" >&2
  exit 1
fi

if [ ! -f "$WORK/app-release.apk" ]; then
  echo "::error::$TAG 里没有 app-release.apk,发版是不是没跑完?" >&2
  exit 1
fi
if [ ! -f "$WORK/app-debug.apk" ]; then
  echo "::warning::$TAG 里没取到 app-debug.apk,跳过它(站点不消费它)"
fi

# 幂等:同名 release 已存在就只覆盖资产,不重建(否则会把已有的说明文字冲掉)
if gh release view "$TAG" -R "$SITE_REPO" >/dev/null 2>&1; then
  echo "已存在 $SITE_REPO @ $TAG,覆盖资产"
else
  # **先建空的 release,再逐个上传**。
  #
  # 原来是把两个附件挂在 `gh release create` 的命令行上。`app-debug.apk` 有 22MB,
  # 走 S3 直传经常 `HTTP 408: Upload body timed out due to inactivity`,
  # 而 create 是**全有或全无**的 —— 一个附件失败,整个 release 都不会被建出来。
  # 症状是「同步脚本说上传超时」,而 `gh release list` 里**连这个 tag 都没有**,
  # 于是站点停在旧版本,而发版流程看上去只是「网络抖动,重试一下」。
  #
  # 拆开之后:release 先存在(页面立刻是对的),附件失败只影响附件,
  # 而且可以单独重试那一个。
  gh release create "$TAG" -R "$SITE_REPO" \
    --title "Gaia $TAG" \
    --notes "盖亚输入法 $TAG。由 $APP_REPO 的发版流水线手动同步。" >/dev/null
  echo "已在 $SITE_REPO 建 $TAG"
fi

# 逐个上传,每个最多 5 次(408 是网络/大小问题,重试通常就好)
#
# ## 两个包的**严重程度不同**(2026-10-04)
#
# 站点的 `deploy.yml` 只取 **app-release.apk** 放进 `docs/public/media/gaia.apk`,
# `Downloads.vue` 里也写明「只提供正式版」。**app-debug.apk 在站点上没有任何消费者**,
# 它只是与 app 仓库 release 的**附件对等**。
#
# 而 debug 包 22MB、release 包 6.9MB —— 差的三倍多,于是大文件那条路
# `HTTP 408: Upload body timed out due to inactivity` / `unexpected EOF`
# 反复出现(实测连续 4 次失败,而 6.9MB 的一次就过)。
#
# 所以:**release 包失败 = 致命**(站点会停在旧版本);**debug 包失败 = 警告后继续**。
# 让一个没人消费的 22MB 附带文件阻塞同步、连带 Pages 不重新部署,是不成比例的失败 ——
# 但静默跳过同样坏(下次就没人知道它没了),所以打 `::warning::`。
for f in app-release.apk app-debug.apk; do
  # 没下到的就不去上传它(否则 gh 会报一个和真实原因无关的错)
  [ -f "$WORK/$f" ] || continue
  ok=0
  for attempt in 1 2 3 4 5; do
    if gh release upload "$TAG" "$WORK/$f" -R "$SITE_REPO" --clobber >/dev/null 2>&1; then
      ok=1; break
    fi
    echo "  $f 第 $attempt 次上传失败,重试"
    sleep $((attempt * 5))
  done
  if [ "$ok" = "1" ]; then
    echo "  ✓ $f"
  elif [ "$f" = "app-release.apk" ]; then
    echo "::error::app-release.apk 连续 5 次上传失败,站点会停在旧版本 —— 不许继续" >&2
    exit 1
  else
    echo "::warning::app-debug.apk 连续 5 次上传失败(22MB 大文件)。站点不消费它(deploy.yml 只取 app-release.apk),继续。"
    echo "::warning::要对齐附件可事后补: gh release upload $TAG <apk> -R $SITE_REPO --clobber"
  fi
done

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
