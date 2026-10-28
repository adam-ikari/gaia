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
# | `v*` tag | CI 的触发条件、release 页标题与资产名 | 人,打 tag 时写 |
#
# 三者没有任何一处强制对齐,于是有两种静默失败:
#
# 1. **Release 说 0.9.9,应用报 0.5.0。** Release 标题与资产名来自 tag,
#    应用版本号来自 `versionName`。两者不一致时,
#    用户从 Release 下到"0.9.9"、装完应用里写着 0.5.0 —— 没有报错,只是骗人。
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

VERSION_NAME="$VERSION_NAME_RAW"
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
  # 不给 tag 时**排除所有指向 HEAD 的 tag**。
  #
  # 常见场景:tag 已经打好了(本地 `git tag -a v0.7.0`),还没推。这时候那个 tag 就挂在
  # HEAD 上,不排除的话自己跟自己比 → 误报「已被用过」。
  #
  # 用「指向同一个 commit」而不是「名字等于某个值」来判,因为我们不知道用户刚打了哪个名字。
  HEAD_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo '')"
  SELF_TAG=""
  if [ -n "$HEAD_COMMIT" ]; then
    SELF_TAG="$(git tag --points-at "$HEAD_COMMIT" 2>/dev/null | head -1)"
  fi
  if [ -n "$SELF_TAG" ]; then
    TAG="$SELF_TAG"
    echo "  (没给 tag,按 HEAD 上的 $SELF_TAG 校验)"
  else
    echo "  (没给 tag,按 HEAD 当前版本 $VERSION_NAME 校验递增性)"
  fi
fi

# ---- versionCode 必须严格大于所有**其他** tag 所带的号 ------------------------
# 比"最近一个 tag"更严:任何一个旧 tag 的 versionCode 都不得 >= 当前值,
# 否则那个版本会被覆盖掉/回退。v0.3.0 没发出去但烧掉了 3 号,所以历史里有个空洞,
# 这条规则天然容忍空洞(只比大小,不比连续)。
#
# ## 为什么要把 $TAG 自己排除掉(2026-10-03 接进 CI 时踩到)
#
# 这个脚本在 CI 里是**在 tag 已经存在之后**跑的 —— tag 的 push 就是工作流的触发条件。
# 所以 `$TAG` 必然出现在 `git tag` 的输出里,而它带的 versionCode **恰好等于**当前值,
# 于是 `>=` 判定成立、每次发版都报 "versionCode 19 已被 tag v0.7.0 用过"——
# 自己跟自己比,永远通不过。
#
# 本地跑时(`不给 tag`)看不到这个问题,因为那时新 tag 还没打。这就是
# 「本地验过、CI 里必挂」的典型:两个入口的**初始状态**不同。
prev_desc=""
conflict=0
while read -r t; do
  # 排除待发布的那个 tag 自己(见上面那段)
  [ "$t" = "$TAG" ] && continue
  # `|| true` 是必需的,不是装饰:本脚本 `set -euo pipefail`,而**不是每个 tag 都有
  # app/build.gradle.kts** —— 2026-10-28 撞上的是本地那个 `dev-latest`(指向一个纯站点
  # commit,那棵树里根本没有这个文件)。`git show` 失败在 pipefail 下把整条管道判死,
  # 于是脚本以 128 **静默退出**(错误被 2>/dev/null 吞掉,连一行都不打)。
  # 静默退出比报错更糟:闸门「没通过」和「没跑」在退出码之外看不出区别。
  vc="$(git show "${t}:${GRADLE}" 2>/dev/null \
        | sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9]\{1,\}\).*/\1/p' | head -1 || true)"
  [ -n "$vc" ] || continue
  if [ "$vc" -ge "$VERSION_CODE" ]; then
    echo "::error::versionCode $VERSION_CODE 已被 tag $t 用过(或更大)。每个发出去的版本必须独占一个递增的 versionCode,否则 Android 拒装升级(等于版本号回退)。" >&2
    conflict=1
  fi
  prev_desc="$prev_desc $t=$vc"
done < <(git tag -l 'v*' --sort=v:refname)

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
