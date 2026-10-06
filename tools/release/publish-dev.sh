#!/usr/bin/env bash
#
# 发布**开发版**(dev 渠道)。CI 的 release.yml 只管正式版;这条管 dev。
#
# ## dev 为什么不是「发布」
#
# 应用仓的 `dev` buildType **刻意没有语义版本号** —— 只有 git hash
# (`0.7.11-dev.<hash>`),见 app/build.gradle.kts。
# 理由:给 master HEAD 的构建声称一个 `0.7.12` 会**烧号**(versionCode 是
# 发布计数器)而且**骗人**(一个从没发布过的号出现在设置页)。
#
# 所以每次 master 构建都发一个 release 是刷屏 + 语义撒谎 ⇒ 用**滚动 release**:
# 固定 tag `dev-latest`,资产 `--clobber` 覆盖。历史靠 git,不靠 release 列表。
#
# ## 与 beta 的区别(别把两条混起来)
#
# | | beta(测试版) | dev(开发版) |
# | --- | --- | --- |
# | 是发布吗 | 是(某个 `v*` 的测试通道) | **不是** |
# | versionName | `0.7.11-beta.<hash>` | `0.7.11-dev.<hash>` |
# | 过 R8 | **是** | **是** |
# | gaia 侧取法 | 最新 **prerelease** | 固定 tag `dev-latest` |
#
# ## dev 也过 R8(2026-10-06 用户定:「任何版本只要公开就需要混淆」)
#
# 这张表原来在 dev 那格写着「否」,理由是「dev 是给开发/自测用的,
# 验功能不验发布形态」。**那个理由混淆了两件事**:本机自测的产物确实
# 不用混淆,但 **dev 渠道在站点上公开可下** —— 一旦公开,判据就变成
# 「任何人都能拿到并反编译」,于是不过混淆等于把类名方法名全交出去。
# 配置侧已改成 `isMinifyEnabled = true`(见 app/build.gradle.kts),
# 闸门 `check_public_r8` 也把 dev 纳入 —— **判据验的是产物,不是这张表**。
#
# ## 用法
#
#     tools/release/publish-dev.sh              # 编 + 发到 gaia 的 dev-latest
#     tools/release/publish-dev.sh --dry-run    # 只编不发,打印会发什么

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 1
cd "$REPO_ROOT" || exit 1

SITE_REPO="${SITE_REPO:-adam-ikari/gaia}"
DEV_TAG="dev-latest"          # 与 gaia 的 deploy.yml 里那个 tag 字面量必须一致
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

die() { printf '\033[31m::error:: %s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---- 工作区必须干净,否则 hash 不代表字节 ------------------------------------
#
# `dev` 的版本号是 `0.7.11-dev.<hash>[.dirty]`。工作区脏的时候那个 hash
# **不代表实际编出来的内容**,而这个包是要发到**公开**站点上的 ——
# 用户按这个 hash 报 bug,他自己编不出同样的东西,复现不了。
#
# ⇒ 这里直接**拒绝**,而不是发一个带 `.dirty` 的包。
#   「标识不诚实」比「不能发布」更糟:后者你会去买单,前者不会。
step "检查工作区"
if [ -n "$(git status --porcelain)" ]; then
  git status --short | sed 's/^/    /'
  die "工作区有未提交改动 —— dev 的版本号是 git hash,脏工作区编出来的包
     hash 对不上实际字节,而这个包会发到公开站点。先提交(或 git stash)。"
fi
ok_hash=$(git rev-parse --short HEAD)
echo "    工作区干净,HEAD=$(git rev-parse HEAD)"
echo "    短 hash=$ok_hash"

# ---- 编 ---------------------------------------------------------------------
step "编 dev 包"
./gradlew --no-daemon :app:assembleDev >/tmp/publish-dev.log 2>&1 \
  || { tail -40 /tmp/publish-dev.log >&2; die "构建挂了(完整日志 /tmp/publish-dev.log)"; }
APK=app/build/outputs/apk/dev/app-dev.apk
[ -s "$APK" ] || die "构建说成功但 $APK 不存在"
SIZE=$(stat -c%s "$APK")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

# **身份从包里读**,不从变量拼 —— 包里的 manifest 是「实际编出了什么」的权威。
# 从别处推一个值出来,就多了一个可能与包不一致的来源。
VER=$("${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0/aapt2" dump badging "$APK" 2>/dev/null \
      | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$VER" ] || die "读不出 APK 的 versionName"

step "这次要发的东西"
printf '    %s\n    %s bytes\n    versionName  %s\n    sha256        %s\n' "$APK" "$SIZE" "$VER" "$SHA"
echo "    → $SITE_REPO @ $DEV_TAG,资产名 app-dev-${ok_hash}.apk"

if [ "$DRY" = 1 ]; then
  echo; echo "    (--dry-run,到此为止)"
  exit 0
fi

# ---- 发 ---------------------------------------------------------------------
#
# **先建空的 release,再逐个上传**。把资产挂在 `gh release create` 命令行上时,
# 那个 22MB 的 debug 包走 S3 直传经常 `HTTP 408`,而 create 是**全有或全无**的
# —— 一个资产失败,整个 release 都不会被建出来(2026-10-05 在 hwkbd_ime 上实测)。
step "发到 $SITE_REPO @ $DEV_TAG"
ASSET="app-dev-${ok_hash}.apk"

# ## 资产名必须来自**本地文件名**,不能靠 `gh release upload` 的 `#`
#
# `gh release upload <path>#<label>` 里 **`#` 后面是 label,不是文件名** ——
# 资产的**文件名**取自 `<path>` 的 basename。
#
# 2026-10-06 实测踩到了:`app-dev.apk#app-dev-4c50b4d.apk` 存下来是
#   name=`app-dev.apk`  label=`app-dev-4c50b4d.apk`
# 而 gaia 的 deploy.yml 是**按资产名**取 hash 的(`startswith("app-dev-")` 后剥前缀),
# 于是它看到 `app-dev.apk` → 剥出 `apk` → 去掉 `.apk` → **空** →
# 走「解析不出身份就跳过」那条路 ⇒ **站点静悄悄没有 dev 渠道**。
#
# ⇒ 所以先把文件按带 hash 的名字暂存,让 basename 本身带身份。
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/$ASSET"
[ -s "$STAGE/$ASSET" ] || die "暂存 $ASSET 失败"
if gh release view "$DEV_TAG" -R "$SITE_REPO" >/dev/null 2>&1; then
  echo "    release 已存在,覆盖资产"
else
  gh release create "$DEV_TAG" -R "$SITE_REPO" --title "dev(滚动)" --prerelease \
    --notes "master HEAD 的开发版构建,**滚动** —— 每次发版都被下一次覆盖。
这个包不做任何保证:不过混淆、没跑端到端测试。资产名带 git hash。

**为什么标成 prerelease**:不标的话 GitHub 会把它当成 latest,
而站点 deploy.yml 的正式版正是按 latest 取的 ⇒ 会去这个 release 里找
\`app-release.apk\` ⇒ 找不到 ⇒ 部署失败、正式版下载按钮挂掉。" >/dev/null \
    || die "建 release 失败"
fi

# ## prerelease 标记必须在 if/else **之外**,而且要回读确认
#
# 2026-10-06 第一次写这段时,它被放进了 `else` 分支(只在**新建**时跑)。
# 而那个 release 已经存在 ⇒ **这段从来没执行过** ⇒ `prerelease=false`
# ⇒ dev-latest 成了 GitHub 的 latest ⇒ 站点部署连续失败两次,
# 正式版下载按钮整个挂掉,而日志里只有一行 `no assets match the file pattern`。
#
# ⇒ 放在 if/else 之外(每次都跑),**并回读确认** ——
#   「命令返回了」不等于「生效了」,这是今天第三次在这件事上栽。
rid=$(gh api "repos/$SITE_REPO/releases/tags/$DEV_TAG" --jq '.id' 2>/dev/null || true)
if [ -n "$rid" ]; then
  gh api -X PATCH "repos/$SITE_REPO/releases/$rid" -f prerelease=true >/dev/null 2>&1 || true
  # 回读要**重试**:这个网络下 `unexpected EOF` 是常态(今天几十次),
  # 一次读到空就断言失败,那是**网络的问题被报成了标记的问题** ——
  # 而这种误报会让人去查一个根本不存在的配置错误。
  is_pre=""
  for a in 1 2 3 4 5; do
    is_pre=$(gh api "repos/$SITE_REPO/releases/$rid" --jq '.prerelease' 2>/dev/null || true)
    [ -n "$is_pre" ] && break
    sleep $((a * 3))
  done
  if [ "$is_pre" != "true" ]; then
    die "$DEV_TAG 没能标成 prerelease(现在是 $is_pre)
     不标的后果不是「dev 渠道显示不出来」,而是**它会 hijack GitHub 的 latest**:
     站点 deploy.yml 的正式版按 latest 取,于是去这个 release 里找
     app-release.apk → 找不到 → 部署失败 → 正式版下载按钮挂掉。"
  fi
  echo "    prerelease ✓ (回读确认过)"
fi

# 上传后**回读资产清单核对大小**:`gh release upload` 失败时退出码可能非零而
# 服务端其实已经存好了(实测过 `unexpected EOF` 但 release 已建成),所以
# 「命令返回了」不等于「东西在」;反过来「命令失败了」也不等于「东西不在」。
# ⇒ 判据落在**远端清单**上。
# **不以命令的退出码为准**:实测 `gh release upload` 失败时退出码非零而服务端
# 其实已经存好了(`unexpected EOF`,而 release 已建成)。所以「命令返回了」不等于
# 「东西在」,「命令失败了」也不等于「东西不在」⇒ 判据落在**远端清单**上。
for a in 1 2 3 4 5; do
  if gh release upload "$DEV_TAG" "$STAGE/$ASSET" -R "$SITE_REPO" --clobber >/dev/null 2>&1; then
    break
  fi
  echo "    第 $a 次上传失败(12MB 走 S3 直传容易 HTTP 408),重试"
  sleep $((a * 5))
done

WANT=$(gh api "repos/$SITE_REPO/releases/tags/$DEV_TAG" \
       --jq ".assets[] | select(.name==\"$ASSET\") | .size" 2>/dev/null | head -1)
if [ -z "$WANT" ]; then
  die "远端清单里没有 $ASSET —— 上传没成,站点这次不会有 dev 渠道"
elif [ "$WANT" != "$SIZE" ]; then
  die "远端 $ASSET 是 $WANT 字节,本地是 $SIZE 字节 ⇒ 传输坏了,别当成成功"
fi
echo "    ✓ $ASSET ($WANT 字节,与本地一致)"

# ## 滚动 release 里必须**只有一个** dev 资产
#
# 每次上传都用带新 hash 的文件名 ⇒ 而资产名带身份是刻意的(身份来自
# 「远端确实存着什么」)。两者相加就是:**资产会一份份堆起来**。
#
# 2026-10-06 实测:发过几次之后 release 里有三份 `app-dev-*.apk`。
# gaia 的 deploy.yml 用 `[...] | last` 挑 —— 那是**碰巧**挑对(依赖 API 返回顺序),
# 而一个 release 里有 N 份「最新版」自相矛盾:最新的**定义**应该唯一。
#
# ## 「查不到」不等于「没什么可删」—— 同一个形状的第三个实例
#
# 第一版写成 `for old in $(gh api ... --jq ...)`。网络抖一下
# (`unexpected EOF`,今天遇到过十几次)那个查询返回**空**,循环体一次都不跑,
# 而脚本**不吭声** —— 删不掉与不需要删,在输出上一模一样。
#
# ⇒ 用**退出码**区分「查询失败」与「结果为空」,并重试。
dev_assets() {
  local out a
  for a in 1 2 3 4 5; do
    if out=$(gh api "repos/$SITE_REPO/releases/tags/$DEV_TAG" --jq \
              '.assets[].name | select(startswith("app-dev-"))' 2>/dev/null); then
      printf '%s\n' "$out"; return 0            # 成功(可能就是空)
    fi
    sleep $((a * 3))
  done
  echo "::error::连续 5 次取不到 $DEV_TAG 的资产清单 —— 不知道该删什么,不继续" >&2
  return 1
}

list=$(dev_assets) || exit 1
for old in $list; do
  [ "$old" = "$ASSET" ] && continue
  if gh release delete-asset "$DEV_TAG" "$old" -R "$SITE_REPO" --yes >/dev/null 2>&1; then
    echo "    删掉旧的 $old"
  else
    echo "::warning::旧的 $old 没删掉 —— 那个 release 里会有多份 dev 资产" >&2
  fi
done

n_left=$(dev_assets | grep -c . || true)
[ "$n_left" = "1" ] || echo "::warning::dev 资产现在有 $n_left 份(应为 1),站点可能挑到旧的" >&2

# ---- 触发部署 ---------------------------------------------------------------
#
# 站点部署**必须走 CI**:Pages 是 `build_type: workflow`,任何站点改动都要构建。
# 而 gaia 是公开仓,它的 Actions **不受私有仓的账单限制**(实测 Deploy site 正常)。
step "触发站点部署(走 CI)"
for a in 1 2 3; do
  if gh workflow run deploy.yml -R "$SITE_REPO" --ref main >/dev/null 2>&1; then
    echo "    已触发"; break
  fi
  echo "    第 $a 次触发失败,重试"; sleep $((a * 5))
done

printf '\n\033[32m已发\033[0m 站点会在 CI 跑完后更新。\n'
printf '验的时候看这两条:\n'
printf '  · %s 的 run 是不是 success\n' "$SITE_REPO"
printf '  · https://adam-ikari.github.io/gaia/media/gaia-dev.apk 是不是 %s 字节\n' "$SIZE"
printf '    (下下来 sha256 应当是 %s)\n' "$SHA"
