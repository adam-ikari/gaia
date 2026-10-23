#!/usr/bin/env bash
#
# 发布**测试版**(beta 渠道)。CI 的 release.yml 只管正式版;这条管 beta。
#
# ## beta 是什么
#
# beta = **独立应用** `dev.hwkbd.ime.beta`(与正式版 `dev.hwkbd.ime` 并存,
# 各自的数据与设置分开,见 app/build.gradle.kts 的 beta 段),
# 版本名 `0.7.11-beta.<git hash>`,**过 R8**。
#
# **身份是 git hash,不是版本号** —— 它不占 versionCode、不打 `v*` tag,
# 所以它**不是「版本发布」**:闸门走 `tools/release/gate.sh --beta`
# (版本漂移 / 版本↔tag 那两步对它不成立,见 docs/versioning.md)。
#
# ## 与 dev 的区别(别把两条混起来)
#
# | | beta(测试版) | dev(开发版) |
# | --- | --- | --- |
# | 是不是发布 | 是(一次构建一个 release) | **不是** |
# | 身份 | 资产名里的 hash | 资产名里的 hash |
# | release | 每次一个 `beta-<hash>`(prerelease) | 固定 tag `dev-latest`,资产覆盖 |
# | 站点取法 | prerelease 里**含 `app-beta-*` 资产**的那个 | 固定 tag `dev-latest` |
#
# ## 站点的取法:按**内容**挑,不按命名约定挑
#
# 站点(deploy.yml)找的是「prerelease 且资产里有 `app-beta-*`」那个 release ——
# 不是「最新 prerelease」,因为 dev 也是 prerelease。
# 身份从**资产名**的 hash 尾巴读(`app-beta-c614c26.apk` → `c614c26`),
# 不是从 tag 推导 —— 资产名是「远端确实存着什么」的权威。
#
# ## 判据(每条都来自一次实测)
#
# · **工作区必须干净**:脏时版本名带 `.dirty`,那个 hash **不代表实际字节**,
#   用户按 hash 报 bug 自己编不出同样的东西。宁可拒绝发布。
# · **资产名取自本地文件名的 basename**:`gh release upload <path>#<label>` 里
#   `#` 后面是 **label 不是文件名**,资产的文件名取自 `<path>` 的 basename ——
#   2026-10-06 实测:存下来 name=`app-dev.apk` 而站点按资产名取 hash,
#   于是站点**静悄悄没有 dev 渠道**。
# · **先建空 release 再上传**:把 22MB 的包挂在 `gh release create` 命令行上时
#   走 S3 直传经常 HTTP 408,而 create 是全有或全无 —— 一个资产失败,
#   整个 release 都建不出来。
# · **prerelease 标记必须在 if/else 之外,并回读确认**:2026-10-06 把它放进了
#   「只在新建时跑」的分支 ⇒ 那个 release 一直没被标成 prerelease ⇒
#   它 hijack 了 GitHub 的 latest。站点现在不消费 latest(只发 β/dev),
#   但 latest 仍是对外的发布状态 —— 测试版不该冒充它。
# · **上传后回读远端资产清单**核对大小:命令返回了 ≠ 东西在;
#   命令失败了 ≠ 东西不在(实测过上传返回非零而服务端其实存好了)。
# · **查询失败与「没有这东西」要分开**:前者重试,后者才是真的没有。
#
# 用法:
#     tools/release/publish-beta.sh              # 编 + 发到 gaia
#     tools/release/publish-beta.sh --dry-run    # 只编不发,打印会发什么
#     tools/release/publish-beta.sh --skip-build # 用已有的 beta 包(不重编)

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 1
cd "$REPO_ROOT" || exit 1

SITE_REPO="${SITE_REPO:-adam-ikari/gaia}"
DRY=0
SKIP_BUILD=0
for a in "$@"; do
  case "$a" in
    --dry-run)    DRY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help)    sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "::error::不认识的参数 $a" >&2; exit 1 ;;
  esac
done

die() { printf '\033[31m::error:: %s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()  { printf '    \033[32m✓ %s\033[0m\n' "$1"; }

command -v gh >/dev/null || die "需要 gh"

# ---- 工作区必须干净 ---------------------------------------------------------
#
# beta 的版本名是 `0.7.11-beta.<hash>`。工作区脏的时候那个 hash **不代表实际
# 编出来的内容** —— 而这个包要发到**公开**站点上,用户按 hash 报 bug,
# 他自己编不出同样的东西,复现不了。
#
# ⇒ 这里直接**拒绝**,而不是发一个带 `.dirty` 的包:
#   「标识不诚实」比「不能发布」更糟 —— 后者你会去买单,前者不会。
step "检查工作区"
if [ -n "$(git status --porcelain)" ]; then
  git status --short | sed 's/^/    /'
  die "工作区有未提交改动 —— beta 的版本号是 git hash,脏工作区编出来的包
     hash 对不上实际字节,而这个包会发到公开站点。先提交(或 git stash)。"
fi
ok_hash=$(git rev-parse --short HEAD)
ok "工作区干净,HEAD=$(git rev-parse HEAD)(短 hash=$ok_hash)"

# ---- 编 ---------------------------------------------------------------------
if [ "$SKIP_BUILD" = 1 ]; then
  step "用已有的 beta 包(不重编)"
else
  step "编 beta 包"
  ./gradlew --no-daemon :app:assembleBeta >/tmp/publish-beta.log 2>&1 \
    || { tail -40 /tmp/publish-beta.log >&2; die "构建挂了(完整日志 /tmp/publish-beta.log)"; }
fi
APK=app/build/outputs/apk/beta/app-beta.apk
[ -s "$APK" ] || die "构建说成功但 $APK 不存在"
SIZE=$(stat -c%s "$APK")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

# **身份从包里读**,不从变量拼 —— 包里的 manifest 是「实际编出了什么」的权威。
# 从别处推一个值出来,就多了一个可能与包不一致的来源。
VER=$("${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0/aapt2" dump badging "$APK" 2>/dev/null \
      | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$VER" ] || die "读不出 APK 的 versionName"
case "$VER" in
  *.dirty) die "版本名带 .dirty($VER)—— 那个 hash 不代表实际字节,不许发布" ;;
  *"-beta.$ok_hash") : ;;
  *) die "版本名($VER)与 HEAD($ok_hash)对不上 —— 编出来的包不是这个 commit 的,不许发布" ;;
esac
PKG=$("${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0/aapt2" dump badging "$APK" 2>/dev/null \
      | sed -n "s/.*package: name='\([^']*\)'.*/\1/p" | head -1)
[ "$PKG" = "dev.hwkbd.ime.beta" ] || die "applicationId 是 $PKG,不是 dev.hwkbd.ime.beta"

step "这次要发的东西"
printf '    %s\n    %s bytes\n    applicationId  %s\n    versionName  %s\n    sha256        %s\n' \
  "$APK" "$SIZE" "$PKG" "$VER" "$SHA"
echo "    → $SITE_REPO @ beta-$ok_hash,资产名 app-beta-$ok_hash.apk"

if [ "$DRY" = 1 ]; then
  echo; echo "    (--dry-run,到此为止)"
  exit 0
fi

# ---- 发 ---------------------------------------------------------------------
#
# **先建空的 release,再逐个上传**(见文件头:挂在 create 命令行上会整包失败)。
ASSET="app-beta-$ok_hash.apk"
TAG="beta-$ok_hash"

# 资产名必须来自**本地文件名**,不能靠 `gh release upload` 的 `#`(见文件头)。
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/$ASSET"
[ -s "$STAGE/$ASSET" ] || die "暂存 $ASSET 失败"

step "发到 $SITE_REPO @ $TAG"
if gh release view "$TAG" -R "$SITE_REPO" >/dev/null 2>&1; then
  echo "    release 已存在,覆盖资产"
else
  gh release create "$TAG" -R "$SITE_REPO" --title "测试版 $ok_hash" --prerelease \
    --notes "master HEAD 的**测试版**构建(\`$VER\`)。

beta 是与正式版并存的独立应用(\`$PKG\`),身份是 **git hash**,不是版本号:
它不占 versionCode、不打 \`v*\` tag。资产名里的 hash 就是「哪个 commit」。

这一版过 R8,单测/JNI 闸门都过了;但**没跑端到端测试**,
真机上的手感与功能仍需自己验。" >/dev/null \
    || die "建 release 失败"
fi

# prerelease 标记必须**每次都跑**并**回读确认**(见文件头:2026-10-06 的事故)。
rid=$(gh api "repos/$SITE_REPO/releases/tags/$TAG" --jq '.id' 2>/dev/null || true)
if [ -n "$rid" ]; then
  gh api -X PATCH "repos/$SITE_REPO/releases/$rid" -f prerelease=true >/dev/null 2>&1 || true
  # 回读要**重试**:这个网络下 `unexpected EOF` 是常态,一次读到空就断言失败,
  # 那是**网络的问题被报成了标记的问题**。
  is_pre=""
  for a in 1 2 3 4 5; do
    is_pre=$(gh api "repos/$SITE_REPO/releases/$rid" --jq '.prerelease' 2>/dev/null || true)
    [ -n "$is_pre" ] && break
    sleep $((a * 3))
  done
  if [ "$is_pre" != "true" ]; then
    die "$TAG 没能标成 prerelease(现在是 $is_pre)
     不标的后果不是「测试版本次不上线」,而是**它会 hijack GitHub 的 latest**:
     latest 会指向一个测试版,而不是最近一次正式发布。"
  fi
  ok "prerelease ✓(回读确认过)"
fi

for a in 1 2 3 4 5; do
  if gh release upload "$TAG" "$STAGE/$ASSET" -R "$SITE_REPO" --clobber >/dev/null 2>&1; then
    break
  fi
  echo "    第 $a 次上传失败(大包走 S3 直传容易 HTTP 408),重试"
  sleep $((a * 5))
done

# 上传后**回读远端清单**核对大小。查询失败 ≠ 东西不在(见文件头)。
remote_asset_size() {
  local out a
  for a in 1 2 3 4 5; do
    if out=$(gh api "repos/$SITE_REPO/releases/tags/$TAG" --jq \
              ".assets[] | select(.name==\"$ASSET\") | .size" 2>/dev/null); then
      printf '%s\n' "$out" | head -1; return 0      # 查询成功(结果**可能就是空**)
    fi
    sleep $((a * 3))
  done
  return 1                                          # 五次都取不到 = 不知道,不是没有
}

if ! WANT=$(remote_asset_size); then
  die "连续 5 次取不到 $TAG 的资产清单 —— 这**不代表上传失败**,别说站点没有测试版渠道。
     自己核对一次:gh api repos/$SITE_REPO/releases/tags/$TAG --jq '.assets[].name'"
elif [ -z "$WANT" ]; then
  die "$TAG 的资产清单里没有 $ASSET —— 上传没成功,站点这次不会有测试版渠道"
elif [ "$WANT" != "$SIZE" ]; then
  die "$ASSET 远端大小 $WANT ≠ 本地 $SIZE —— 传过去的不是这个包"
fi
ok "远端资产 $ASSET 大小一致($SIZE bytes)"

step "下一步"
echo "    站点还不会自动出现测试版按钮 —— 那是 publish-site.sh 的活:"
echo "      tools/release/publish-site.sh"
