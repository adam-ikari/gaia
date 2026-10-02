#!/usr/bin/env bash
# 校验版本号与 tag 一致、versionCode 严格递增。发版前跑,CI 里也跑。
#
# ## 为什么不靠人记
#
# 版本号散在三个地方、互不校验,历史上真的错过:
#
# | 地方 | 取什么 | 谁填 |
# |---|---|---|
# | `app/build.gradle.kts` 的 `versionName` | 应用自己报的版本 | 人,改代码时顺手改 |
# | `app/build.gradle.kts` 的 `versionCode` | Android 升级判据 | 人,改代码时顺手改 |
# | `v*` tag | CI 的触发条件、release 页标题、**站点页面显示的版本号** | 人,打 tag 时写 |
#
# 三者没有任何一处强制对齐,于是有两种静默失败:
#
# 1. **站点说 0.9.9,应用报 0.5.0。** 站点版本号来自 tag
#    (`hwkbd_ime-web/.github/workflows/deploy.yml` 把 tag 剥掉 `v` 注入
#    `VITE_APP_VERSION`),应用版本号来自 `versionName`。两者不一致时,
#    用户从站点下到"0.9.9"、装完应用里写着 0.5.0 —— 没有报错,只是骗人。
# 2. **忘了加 versionCode ⇒ 装不上。** Android 要求升级时 `versionCode` **严格大于**
#    已装版本,否则 PackageInstaller 拒装(`INSTALL_FAILED_VERSION_DOWNGRADE`)。
#    站点是直接发 APK 文件(不走商店),所以这一步只有用户装的时候才会暴露 ——
#    而那时已经在用旧包了。
#
# 两条都只能靠发版前的一次检查拦住,所以这个脚本是发版流程的**一步**,不是可选项。
#
# 用法:
#   tools/release/check-version.sh              # 拿当前 HEAD 当待发版本
#   tools/release/check-version.sh v0.5.1      # 或指定即将打的 tag
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

GRADLE=app/build.gradle.kts
TAG="${1:-}"

fail() { echo "::error::$1" >&2; exit 1; }

# ---- 取 build.gradle.kts 里的两个号 -------------------------------------------
# 只认 defaultConfig 那一段:release/debug 的 buildTypes 不改版本号,
# 但文件里可能出现别处提到这两个名字,所以锚在 android { 之后取第一个。
read_gradle_field() {
  sed -n 's/^[[:space:]]*'"$1"'[[:space:]]*=[[:space:]]*\([^[:space:]]*\).*/\1/p' "$GRADLE" | head -1
}

VERSION_NAME_RAW="$(read_gradle_field versionName)" || true
VERSION_CODE_RAW="$(read_gradle_field versionCode)" || true

[ -n "$VERSION_NAME_RAW" ] || fail "$GRADLE 里读不到 versionName"
[ -n "$VERSION_CODE_RAW" ] || fail "$GRADLE 里读不到 versionCode"

VERSION_NAME="${VERSION_NAME//\"/}"
VERSION_NAME="${VERSION_NAME//\'/}"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "versionName 必须是 三段数字(现在是 '$VERSION_NAME'),tag 才有统一格式可对"

[[ "$VERSION_CODE_RAW" =~ ^[0-9]+$ ]] \
  || fail "versionCode 必须是正整数(现在是 '$VERSION_CODE_RAW')"
VERSION_CODE="$VERSION_CODE_RAW"

# ---- tag 与 versionName 必须一致 ----------------------------------------------
if [ -n "$TAG" ]; then
  [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || fail "tag 必须是 v三段数字(现在是 '$TAG'),否则 release.yml 的 tags: ['v*'] 与站点都对不齐"
  [ "$TAG" = "v$VERSION_NAME" ] \
    || fail "tag '$TAG' 与 $GRADLE 的 versionName '$VERSION_NAME' 不一致 —— 站点页面显示的是 tag,应用报的是 versionName,两者不一致就是骗用户"
else
  echo "  (没给 tag,按 HEAD 当前版本 $VERSION_NAME 校验递增性)"
fi

# ---- versionCode 必须严格大于所有已存在的 tag 所带的号 ------------------------
# 比"最近一个 tag"更严:任何一个旧 tag 的 versionCode 都不得 >= 当前值,
# 否则那个版本会被覆盖掉/回退。v0.3.0 没发出去但烧掉了 3 号,所以历史里有个空洞,
# 这条规则天然容忍空洞(只比大小,不比连续)。
prev_desc=""
conflict=0
while read -r t; do
  vc="$(git show "${t}:${GRADLE}" 2>/dev/null \
        | sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9]\{1,\}\).*/\1/p' | head -1)"
  [ -n "$vc" ] || continue
  if [ "$vc" -ge "$VERSION_CODE" ]; then
    echo "::error::versionCode $VERSION_CODE 已被 tag $t 用过(或更大)。每个发出去的版本必须独占一个递增的 versionCode,否则 Android 拒装升级(等于版本号回退)。" >&2
    conflict=1
  fi
  prev_desc="$prev_desc $t=$vc"
done < <(git tag --sort=v:refname)

[ "$conflict" -eq 0 ] || exit 1

echo "  versionName  $VERSION_NAME"
echo "  versionCode  $VERSION_CODE"
echo "  历史 tag     ${prev_desc# }"

# ---- 顺带提醒:未提交 / 未推的版本号改动会打 tag 打出错东西 --------------------
if [ -z "$TAG" ]; then
  if ! git diff --quiet -- "$GRADLE"; then
    echo "::warning::$GRADLE 有未提交的改动,打 tag 前先提交 —— tag 指向的是 commit,不是工作区"
  fi
fi

echo "  ✓ 版本号一致"
