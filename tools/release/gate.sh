#!/usr/bin/env bash
#
# 发布闸门:**唯一**的承重检查入口。CI 和本地跑的是同一段代码。
#
# ## 为什么要有这个文件
#
# 原本这些检查散在 release.yml 的 step 里,规则写在 docs/versioning.md 里。
# 那样有两个问题:
#
# 1. **「CI 是绿的」不等于「检查过了」。** GitHub Actions 在私有仓超额度时
#    会让 job **一个 step 都不执行**就失败:
#
#        The job was not started because recent account payments have failed
#
#    2 秒、`conclusion: failure`、零检查。徽章是红的,看起来像「测试挂了」,
#    实际是「什么都没发生」。**这是 fail-open** —— 它制造了有闸门的错觉,
#    比没有闸门更危险,因为人会因此放心。
# 2. **本地和 CI 跑的不是同一段代码。** 本地跑一遍 `check-version.sh`、
#    CI 里再写一遍,改了一边忘了另一边,规则就只在一半的地方生效。
#
# ⇒ 规则收敛到这个脚本。**判定依据是它的退出码**,不是任何 run 的状态。
#
# ## 用法
#
#     tools/release/gate.sh                  # 完整闸门(与 release.yml 同一条链)
#     tools/release/gate.sh --quick          # 只跑单测(内循环)
#     tools/release/gate.sh --tag v0.7.10    # 指定待发布 tag
#     tools/release/gate.sh --release        # 发版模式:额外要求本 commit 引用 E2E run
#     tools/release/gate.sh --beta           # 测试版模式:跳过「版本号」那两步(见下)
#
# 退出码非 0 ⇒ 不许发版。没有「部分通过」这回事。

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO_ROOT" || exit 1

QUICK=0
RELEASE=0
BETA=0
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --quick)   QUICK=1 ;;
    --release) RELEASE=1 ;;
    --beta)    BETA=1 ;;
    --tag)     TAG="${2:-}"; shift ;;
    -h|--help)
      sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数: $1(用 --help)" >&2; exit 2 ;;
  esac
  shift
done

STEP=0
step() { STEP=$((STEP + 1)); printf '\n\033[1m[%d/%d] %s\033[0m\n' "$STEP" "$TOTAL" "$1"; }
ok()   { printf '      \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '\n\033[31m::error:: %s\033[0m\n' "$1" >&2; exit 1; }

TOTAL=9
[ "$QUICK" = 1 ] && TOTAL=3
# 测试版跳过「版本号漂移」「版本号 ↔ tag 对齐」两步 —— 见 check_beta_notes。
[ "$BETA" = 1 ] && TOTAL=7

# CI 固定的工具版本,三处流水线一致。改这里要同步改三个 yml。
CI_CMAKE=3.22.1

# ---------------------------------------------------------------------------
# 1. 环境预检
#
# ## 为什么要预检:报错要指向原因,而不是指向下一个症状
#
# 没装 NDK 时 CMake 会报
#     The source directory .../deps/yaml-cpp does not exist
# 或者
#     CMakeLists.txt:158「找不到 boost 头文件」
#
# 两条都指不到真正的原因(是没 checkout 子模块 / 没装 NDK),
# 于是每次都要重新绕一圈。NDK 版本必须与 app/build.gradle.kts 的 ndkVersion
# 一致 —— 不同 NDK 编出来的 .so 有差异,而「本地能跑线上崩」从 Kotlin 侧看不出来。
# ---------------------------------------------------------------------------
preflight() {
  step "环境预检"
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  [ -d "$sdk" ] || die "找不到 Android SDK(ANDROID_HOME / ANDROID_SDK_ROOT)。runner 上它在 /usr/local/lib/android/sdk"

  local want_ndk
  want_ndk=$(sed -n 's/.*ndkVersion *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
  [ -n "$want_ndk" ] || die "从 app/build.gradle.kts 读不出 ndkVersion"

  if [ ! -d "$sdk/ndk/$want_ndk" ]; then
    local have
    have=$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d -printf '%f ' 2>/dev/null || true)
    die "缺 NDK $want_ndk(已装: ${have:-无})。装它:
       export PATH=\"\$PATH:$sdk/cmdline-tools/latest/bin\"
       yes | sdkmanager --licenses >/dev/null || true
       sdkmanager --install 'ndk;$want_ndk' 'cmake;3.22.1'"
  fi
  ok "NDK $want_ndk"

  # CMake 走 AGP 的解析顺序:local.properties 的 cmake.dir → $SDK/cmake/<v> → PATH。
  # **别去查固定路径** —— 本机就压根没有 $SDK/cmake,是回落到了 PATH 上的
  # 一个别的版本(见下面那个版本对账)。
  cmake_where() {
    if [ -f local.properties ]; then
      local d
      d=$(sed -n 's/^cmake\.dir *= *//p' local.properties | tail -1)
      [ -n "$d" ] && [ -x "$d/bin/cmake" ] && { echo "$d/bin/cmake"; return; }
    fi
    find "$sdk/cmake" -maxdepth 2 -name cmake -type f 2>/dev/null | sort | tail -1 || true
    command -v cmake 2>/dev/null || true
  }
  cmake_ver=$(cmake_where)
  [ -n "$cmake_ver" ] || die "找不到 cmake(装:sdkmanager --install 'cmake;$CI_CMAKE')"
  cmake_ver=$("$cmake_ver" --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

  # **CI 固定用 3.22.1,本地不一定。** 2026-10-05 实测到本机回落到了 PATH 上的
  # 3.31.10,而 $SDK/cmake 根本不存在 —— 于是本地编出的包与 CI 编的不是同一套
  # 工具链。这不 fail(本地闸门不是产物闸门),但必须**说出来**,
  # 因为它的失效方式正是「本地能跑线上崩」。
  if [ "$cmake_ver" != "$CI_CMAKE" ]; then
    printf '      \033[33m!\033[0m CMake %s(CI 用 %s)\n' "$cmake_ver" "$CI_CMAKE"
    printf '        本地编出的包与 CI 的不是同一套工具链。本地过了不代表线上过。\n'
  else
    ok "CMake $cmake_ver"
  fi

  # librime 是 submodule,它的 vendored deps 又是子模块。不带 submodules
  # checkout 就是空目录 —— 这是「CI 挂了」最常见的一种真实原因。
  #
  # 路径是 app/src/main/cpp/librime(见 .gitmodules),**不是仓库根的 librime/**。
  local L=app/src/main/cpp/librime
  [ -d "$L/src" ] || die "$L/ 是空的。checkout 必须带 submodules: recursive"
  ok "librime 子模块在位"
}

# ---------------------------------------------------------------------------
# 2. 版本号漂移:**master 不许长期自称一个没发过的版本**
#
# ## 这条规则是从一次真实的状态里长出来的
#
# 2026-10-05 的现场:`3eac2f1`(一个跟版本无关的功能 commit)顺手把
# versionCode 提到 29 / versionName 提到 0.7.10。于是 14 个 commit 过去,
# master 一直在自称 0.7.10,而已发布的最新版本是 0.7.9,**没有对应的 tag**。
#
# 代价不是「难看」,是两条具体的:
#
# - **master 编出来的包自称 0.7.10,但 0.7.10 从不存在。** 用户报 bug 说
#   「我用的 0.7.10」时,这句话无法定位到任何已发布的产物。
# - **`check-version.sh` 分不清「master 正在开发 0.7.10」和「0.7.10 即将发布」。**
#
# ## 规则
#
# **版本号只在发版那一个 commit 里改,且那个 commit 必须是 tip。**
# 于是恒等式成立:
#
#     master 的版本号 == 最新已发布版本的版本号
#
# tag 一推,「已发布」就变成新版本 —— bump 与 tag 相邻,不是巧合而是构造。
#
# 判据(全部可执行,不靠记性):
#   T = 最高的 v* tag;看 T..HEAD 里谁改了 versionCode/versionName;
#   没有人改           → 正常开发状态(HEAD 就是已发布版本)
#   改了且就是 HEAD   → 待发版(打 tag 即可)
#   改了但不是 HEAD   → 违规:报出那个 commit
# ---------------------------------------------------------------------------
ver_of() {  # ver_of <ref> → "<versionCode> <versionName>"
  git show "$1:app/build.gradle.kts" 2>/dev/null | awk '
    # **必须锚到行首**(只允许前导空白):beta/dev 用的是 `versionNameSuffix`,
    # 而松匹配 `/versionName *=/` 会把它也当成 versionName ——
    # `versionNameSuffix` 里 `versionName` 后面跟的是 `S`,但 `Suffix = …`
    # 那段又含 ` =`,整个串于是被匹配上了,值取成 `-dev.<hash>`。
    #
    # 2026-10-06 实测踩到:漂移检查报「master 的版本号(30 dev-<hash>)」,
    # 而 build.gradle.kts 里明明是 `versionCode = 30` / `versionName = "0.7.11"`。
    # 报错信息里的错值会把人带去查一个不存在的漂移 —— 比不报还费时间。
    /^[[:space:]]*versionCode *=/ { gsub(/[^0-9]/,"",$0); c=$0 }
    /^[[:space:]]*versionName *=/ { if (match($0, /"[^"]*"/)) { n=substr($0,RSTART+1,RLENGTH-2) } }
    END { print c, n }'
}

check_drift() {
  step "版本号漂移(master 不许自称未发布的版本)"
  local top
  top=$(git tag -l 'v*' --sort=-v:refname | head -1)
  if [ -z "$top" ]; then
    ok "还没有任何 tag,无漂移可言"
    return 0
  fi

  # 比**值**,不比「哪个 commit 碰过这个文件」——
  # 后者会把「顺手修了个无关 typo」也算成一次 bump,判据不准。
  local head_v tag_v
  head_v=$(ver_of HEAD)
  tag_v=$(ver_of "$top")

  if [ "$head_v" = "$tag_v" ]; then
    ok "master = $top = $tag_v(master 承袭已发布版本)"
    return 0
  fi

  # 值不同:只有当 **HEAD 自己**改了版本号,才是「待发版」
  if ! git diff-tree --no-commit-id --name-only -r HEAD -- app/build.gradle.kts | grep -q .; then
    : # HEAD 没碰版本号 ⇒ 说明是更早的某个 commit 改的,那就是漂移
  else
    ok "版本号在 tip 上改了($tag_v → $head_v)—— 待发版"
    return 0
  fi

  die "master 的版本号($head_v)与最新 tag $top($tag_v)不一致,且不是 tip 改的
     ⇒ master 自称一个从未发布的版本,已漂移 $(git rev-list --count "$top..HEAD") 个 commit。
     哪一步改的:
       git log -p --oneline $top..HEAD -- app/build.gradle.kts | grep -E '^[0-9a-f]{7} |^\+.*version(Code|Name)'

     修法二选一:
       · 该版本确实要发 → 版本号只允许出现在发版 commit 里。先把这个 commit 的
         版本号改动撤掉(revert),再单独做一个发版 commit 改版本号。
       · 不发 → 把版本号改回 $tag_v,恢复「master 承袭已发布版本」。

     规则见 docs/branching.md「版本号只在发版那一个 commit 里改」"
}

# ---------------------------------------------------------------------------
# 3. 版本号 ↔ tag 对齐(含「不撞旧号」)
# ---------------------------------------------------------------------------
check_version() {
  step "版本号 ↔ tag 对齐"
  if [ -n "$TAG" ]; then
    tools/release/check-version.sh "$TAG" || die "check-version.sh $TAG 没过"
  else
    tools/release/check-version.sh || die "check-version.sh(HEAD)没过"
  fi
  ok "check-version.sh"
}

# ---------------------------------------------------------------------------
# 3'. 测试版模式:为什么跳过「版本号」那两步
#
# ## 那两步是为「版本发布」准备的,不是为「测试版」准备的
#
# · 「版本号漂移」:master 必须承袭**已发布**的版本号(不许自称没发布过的号);
# · 「版本号 ↔ tag 对齐」:待发布的 tag 必须是 `v` + versionName,且 versionCode
#   不与任何旧 tag 撞。
#
# 测试版(beta)两样都不成立:
#   · beta 的身份是 **git hash**(`0.7.11-beta.<hash>`),不是版本号 —— 它不占用
#     任何 versionCode,也不需要 tag(见 app/build.gradle.kts 的 beta 段);
#   · master 的版本号**本来就该**等于已发布版本(那是「承袭」的定义),
#     于是「不撞旧 tag」这条对 master HEAD 永远是假的 —— 那个号已经被
#     已发布的 v0.7.11 用掉了。
#
# ⇒ beta 跑的是**产物侧**那几步(单测 / 构建 / R8 / JNI),版本那两步不适用。
#   但「跳过了哪一步」必须**打出来** —— 闸门最怕的是「看起来跑过了」。
# ---------------------------------------------------------------------------
check_beta_notes() {
  step "版本号两步(测试版模式跳过)"
  ok "跳过「版本号漂移」:beta 的身份是 git hash,不吃 versionCode"
  ok "跳过「版本号 ↔ tag 对齐」:beta 不打 v* tag,master 承袭已发布版本"
  printf '      \033[33m(测试版不是版本发布;下面跑的是产物侧那几步)\033[0m\n'
}

# ---------------------------------------------------------------------------
# 4/5. 单测 + 构建
# ---------------------------------------------------------------------------
run_tests() {
  step "单元测试"
  ./gradlew --no-daemon :app:testDebugUnitTest >/tmp/gate-test.log 2>&1 \
    || { tail -40 /tmp/gate-test.log >&2; die "单测挂了(完整日志 /tmp/gate-test.log)"; }
  ok "单测通过"
}

assemble() {
  step "构建 release + debug + beta"
  # **dev 也要编**:它现在过 R8(公开渠道一律混淆)⇒ 与 beta 同样有
  # 「R8 削掉 JNI 入口名」的风险,而那个风险只在**产物**里看得见。
  ./gradlew --no-daemon :app:assembleRelease :app:assembleDebug \
                        :app:assembleBeta :app:assembleDev >/tmp/gate-build.log 2>&1 \
    || { tail -40 /tmp/gate-build.log >&2; die "构建挂了(完整日志 /tmp/gate-build.log)"; }
  for a in app/build/outputs/apk/release/app-release.apk \
           app/build/outputs/apk/debug/app-debug.apk \
           app/build/outputs/apk/beta/app-beta.apk \
           app/build/outputs/apk/dev/app-dev.apk; do
    [ -s "$a" ] || die "构建说成功但 $a 不存在/是空的"
  done
  ok "四个包都在(release / debug / beta / dev)"
}

# ---------------------------------------------------------------------------
# 5b. **公开渠道**必须真的过了 R8 —— 验产物,不验配置
#
# ## 判据是「公开」,不是「某个渠道名」
#
# 2026-10-06 用户定:「任何版本只要公开就需要混淆」。
# 写死成 `check_beta_r8` 就错了 —— 那个判据问的是「beta 过没过 R8」,
# 而真正要问的是「**有没有人能从公网拿到这个字节**」。
# dev 渠道挂在站点上,同样是公开的,而我第一版把它的 R8 关了。
#
# ⇒ 判据落在**产物**上,而且遍历所有公开渠道:有 mapping 才算数。
#
# ## 为什么不能用 `initWith` 继承
#
# beta 用 `initWith(getByName("release"))` 建,注释里写着「R8 开着(继承)」。
# **实际没继承**:AGP 对 buildType 的配置延迟生效,`initWith` 执行时
# release 块里的 `isMinifyEnabled = true` 还没落到 DSL 对象上。
# 症状:beta 编出来 9.1MB 而 release 6.9MB,`mapping/beta/` 里没有 mapping.txt。
#
# ## 与 check_jni 同源
#
# 那闸门存在的理由是「E2E 跑 debug 包、不过 R8,所以这类问题在 CI 上
# 结构性不可见」—— 一个渠道不过 R8,同一个盲区就又回来了。
check_public_r8() {
  step "公开渠道真的过了 R8 吗(验产物)"
  local vt mapping bad=0
  # 站点只发两个渠道:gaia-beta(主下载)、gaia-dev。
  # release 也一并验 —— 它仍是公开渠道,只是不再进站点。
  for vt in release beta dev; do
    mapping="app/build/outputs/mapping/$vt/mapping.txt"
    if [ ! -s "$mapping" ]; then
      bad=1
    else
      ok "$vt: mapping.txt $(stat -c%s "$mapping") 字节"
    fi
  done
  if [ "$bad" = 1 ]; then
    echo "::error::有渠道没有 mapping.txt ⇒ 它没过 R8。" >&2
    echo "::error::凡是**站点会去取**的渠道都必须混淆:不过混淆等于把类名方法名" >&2
    echo "::error::交给任何能下载到它的人,任何校验都能被 patch 掉。" >&2
    echo "::error::对应 build.gradle.kts 里那个 buildType 的 isMinifyEnabled ——" >&2
    echo "::error::**显式**写,不能靠 initWith(它不继承,release 块是延迟生效的)。" >&2
    return 1
  fi
}

# ---------------------------------------------------------------------------
# 5c. 签名核对:这几个包是不是**同一把钥匙**签的
#
# 「CI 是绿的」从来不含签名这件事 —— 因为签名的钥匙和口令就写死在
# `app/build.gradle.kts` 里,想不签名都难。2026-10-26 仓库转公开,钥匙挪到
# local.properties / SIGNING_*,于是「两处都没配」第一次变成一个**走得通**的状态:
# gradle 成功、包产出、sha256 算好、站点更新 —— 只是那个包装不上。
#
# ⇒ 判据落在产物上,而且验的不只是「有没有签」:证书 SHA-256 必须是发布用的那把。
# 一条判据同时抓两种错:未签名,以及换了一把钥匙(后者等于老用户不能原地升级,
# 而它在任何构建日志里都不显眼)。指纹本身是公开信息(装了包的人 keytool 就有),
# 所以它可以写进版本库 —— 需要保密的是私钥,不是「这把公钥的摘要」。
# ---------------------------------------------------------------------------
check_signing() {
  step "签名核对(验产物)"
  local want=26bb762cac05ff1edd02fafc2a3cc5892242c87c06c904ceb9474761d2218dcd
  local bin
  bin=$(command -v apksigner 2>/dev/null || ls "${ANDROID_HOME:-}"/build-tools/*/apksigner 2>/dev/null | tail -1)
  [ -n "$bin" ] || die "找不到 apksigner(ANDROID_HOME=${ANDROID_HOME:-未设置})"
  local v apk got bad=0
  for v in release debug beta dev; do
    apk="app/build/outputs/apk/$v/app-$v.apk"
    [ -s "$apk" ] || { printf '      \033[31m✗\033[0m %s: 没有 %s\n' "$v" "$apk"; bad=1; continue; }
    got=$("$bin" verify --print-certs "$apk" 2>/dev/null \
          | awk '/certificate SHA-256 digest:/{print tolower($NF); exit}')
    if [ -z "$got" ]; then
      printf '      \033[31m✗\033[0m %s: **未签名** —— 装上会报「软件包无效」\n' "$v"; bad=1
    elif [ "$got" != "$want" ]; then
      printf '      \033[31m✗\033[0m %s: 证书是 %s\n' "$v" "$got"
      printf '          不是发布用的那把(%s)⇒ 老用户无法原地升级\n' "$want"; bad=1
    else
      ok "$v: $got"
    fi
  done
  [ "$bad" = 0 ] || die "签名不对,别发。钥匙来源二选一:local.properties 的 signing.*(本机)或 SIGNING_*(CI 的 secrets)。
     两处都没有 ⇒ 编出来的是裸包:装不上,而构建日志每一步都是绿的。"
}

# ---------------------------------------------------------------------------
# 6. JNI 闸门:R8 有没有削掉入口名
#
# R8 只看得到显式引用,而 JNI 的方法名是 native 侧按**字符串**解析的
# (`Java_<类>_<方法>`),Kotlin 那边没有任何编译期联系 ⇒ 改名毫无代价。
# 症状是运行期 UnsatisfiedLinkError,而且 System.loadLibrary 成功、
# available 是 true,**看起来一切正常**。
#
# 这个闸门在 2026-10-04 抓到过一次真事故:已发布的 v0.7.1 里 14 个 JNI 入口
# 有 7 个被改名 ⇒ 真机上拼音引擎整个不可用。而 E2E 跑的是 debug 包
# (不过 R8)⇒ **CI 上结构性看不到**。
#
# 所以它必须在**上传与发布之前**,且必须验 release 包。
# ---------------------------------------------------------------------------
check_jni() {
  step "JNI 闸门(R8 有没有削掉入口名)"
  [ -x tools/release/check-jni-symbols.py ] || chmod +x tools/release/check-jni-symbols.py
  # **beta 也在列**:它是唯一过 R8 又会装到用户/测试者手上的那个。
  # release 过 R8 但先到 release;beta 过 R8 且更早暴露问题 —— 两个都要验。
  # dev 与 beta 都在列:它们**都过 R8**且都在站点上公开
  # (2026-10-06 起 dev 也开混淆)。debug 不在风险面里 —— 它不过 R8,
  # 那正是这个闸门当初存在的原因(见上面那段注释)。
  tools/release/check-jni-symbols.py \
    app/build/outputs/apk/release/app-release.apk \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/beta/app-beta.apk \
    app/build/outputs/apk/dev/app-dev.apk || die "JNI 闸门没过"
}

# ---------------------------------------------------------------------------
# 7. 发版模式:本 commit 必须引用一次 E2E 证据
#
# ## 为什么要有这一步
#
# E2E 是**发版前的人工前置**,不是产物闸门(它装 debug 包、不过 R8,
# 验的是代码不是产物;硬接成 job 前置要重构成 reusable workflow,
# 而换来的仍不是「产物被验过」)。人工前置最容易失效的方式是「忘了」。
# 所以要求**把证据记进 commit message** —— 进了 git,事后可查,脚本也查得了。
#
# ## 判据跟着 E2E 的位置一起改(2026-10-06)
#
# 原来找的是 `actions/runs/<id>`,因为 E2E 跑在 CI 上。**E2E 已经搬回本地真机**
# (见 `tools/aitest/local.sh`),CI 上不再有这条流水线 ⇒ 那个 URL 永远不会出现,
# 判据会稳定误报 —— 而**会误报的闸门只会被绕过去**,等于没有。
#
# 本地跑出来的证据落在 `tools/aitest/evidence/<时间戳>/`,目录名是 ISO 时间戳,
# 所以判据改成「引用一个真实存在的 evidence 目录」。比 URL 判据强一档:
# 编不出来的,因为目录要么在要么不在。
# ---------------------------------------------------------------------------
check_e2e_cited() {
  step "E2E 证据引用(--release)"
  local msg ref
  msg=$(git log -1 --format=%B HEAD)

  # ① 引用了本地 evidence 目录 —— 要求它**真的存在**
  ref=$(printf '%s' "$msg" | grep -oE 'tools/aitest/evidence/[0-9TZ:.-]+' | head -1)
  if [ -n "$ref" ]; then
    if [ -d "$ref" ]; then
      ok "引用了本地 E2E 证据目录(存在):$ref"
      return 0
    fi
    echo "::error::commit 引用了 $ref,但那个目录不存在 —— 证据是编的" >&2
    return 1
  fi

  # ② 明示跳过
  if printf '%s' "$msg" | grep -qiE '无 E2E|no e2e|skipped e2e'; then
    ok "commit 明示跳过 E2E(理由在 message 里)"
    return 0
  fi
  die "发版 commit 既没有引用 E2E 证据,也没写明为什么不做。
     理由:E2E 跑 debug 包、验的是代码不是产物;但它是发版前唯一的
     端到端回归,「没跑」必须是**写下来的**决定,不能是默认。
     做法:
       · 在真机上跑一次:tools/aitest/local.sh
         然后把证据目录名贴进这个 commit 的 message,例如
           tools/aitest/evidence/2026-10-06T02-14-33-120Z
         (脚本会验那个目录真的存在,编不出来)
       · 或者确实不跑,就写「无 E2E:<原因>」"
}

# ---------------------------------------------------------------------------
if [ "$QUICK" = 1 ]; then
  preflight; check_drift; run_tests
  printf '\n\033[32m闸门(quick)通过\033[0m —— 只跑了单测,不算发版通过。\n'
  exit 0
fi

printf '\033[1m发布闸门\033[0m  repo=%s  HEAD=%s\n' "$REPO_ROOT" "$(git log --oneline -1 | cut -c1-8)"
preflight
if [ "$BETA" = 1 ]; then
  check_beta_notes
else
  check_drift
  check_version
fi
run_tests
assemble
check_public_r8
check_signing
check_jni
[ "$RELEASE" = 1 ] && check_e2e_cited

printf '\n\033[32m闸门通过\033[0m\n'
if [ "$RELEASE" != 1 ]; then
  printf '提醒:这**不等于**产物侧全查了。签名与 R8 已验过产物,剩下的盲区是 E2E\n'
  printf '      (它跑 debug 包,验的是代码不是产物),见 docs/branching.md「四道闸门,各有各的覆盖面」。\n'
fi
