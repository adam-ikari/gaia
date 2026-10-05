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
#
# 退出码非 0 ⇒ 不许发版。没有「部分通过」这回事。

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO_ROOT" || exit 1

QUICK=0
RELEASE=0
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --quick)   QUICK=1 ;;
    --release) RELEASE=1 ;;
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

TOTAL=7
[ "$QUICK" = 1 ] && TOTAL=3

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
    /versionCode *=/ { gsub(/[^0-9]/,"",$0); c=$0 }
    /versionName *=/ { if (match($0, /"[^"]*"/)) { n=substr($0,RSTART+1,RLENGTH-2) } }
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
# 4/5. 单测 + 构建
# ---------------------------------------------------------------------------
run_tests() {
  step "单元测试"
  ./gradlew --no-daemon :app:testDebugUnitTest >/tmp/gate-test.log 2>&1 \
    || { tail -40 /tmp/gate-test.log >&2; die "单测挂了(完整日志 /tmp/gate-test.log)"; }
  ok "单测通过"
}

assemble() {
  step "构建 release + debug"
  ./gradlew --no-daemon :app:assembleRelease :app:assembleDebug >/tmp/gate-build.log 2>&1 \
    || { tail -40 /tmp/gate-build.log >&2; die "构建挂了(完整日志 /tmp/gate-build.log)"; }
  for a in app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/debug/app-debug.apk; do
    [ -s "$a" ] || die "构建说成功但 $a 不存在/是空的"
  done
  ok "两个包都在"
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
  tools/release/check-jni-symbols.py \
    app/build/outputs/apk/release/app-release.apk \
    app/build/outputs/apk/debug/app-debug.apk || die "JNI 闸门没过"
}

# ---------------------------------------------------------------------------
# 7. 发版模式:本 commit 必须引用 E2E 的 run
#
# ## 为什么要有这一步
#
# 现状:E2E 与 release 都在 `push: tags: ['v*']` 上触发,**互相并行、没有依赖**。
# 所以 E2E 红了而 release 照样绿 —— 又一处 fail-open。
#
# ## 但先说清 E2E 到底能保证什么
#
# E2E 装的是 **debug 包,不过 R8**。所以它验的是「这份**代码**在模拟器上
# 能打字、能选字、时序对」,**不是**「这份**产物**能用」。产物侧的风险
# (R8 削名、版本号错、没签名)归 check-jni-symbols / check-version / 签名核对。
#
# 硬要把 E2E 接成 release 的前置(job 级 needs 跨不了 workflow,得重构成
# reusable workflow,代价大且丢掉了手动 dispatch 的入口),换来的仍然不是
# 「产物被验过」。⇒ 诚实做法:**E2E 是发版前的人工前置,不是产物闸门。**
#
# 人工前置最容易失效的方式是「忘了」。所以要求**把 run 记进 commit message**
# —— 于是它进了 git,事后可查,而且这一步是脚本能查的。
# ---------------------------------------------------------------------------
check_e2e_cited() {
  step "E2E run 引用(--release)"
  local msg
  msg=$(git log -1 --format=%B HEAD)
  if printf '%s' "$msg" | grep -qE 'actions/runs/[0-9]+'; then
    ok "commit 引用了 E2E run:$(printf '%s' "$msg" | grep -oE 'actions/runs/[0-9]+' | head -1)"
    return 0
  fi
  if printf '%s' "$msg" | grep -qiE '无 E2E|no e2e|skipped e2e'; then
    ok "commit 明示跳过 E2E(理由在 message 里)"
    return 0
  fi
  die "发版 commit 既没有引用 E2E run,也没写明为什么不做。
     理由:E2E 跑 debug 包,验的是代码不是产物;但它是发版前唯一的
     端到端回归,「没跑」必须是**写下来的**决定,不能是默认。
     做法:
       · 跑完 E2E,把 run 链接贴进这个 commit 的 message
         (gh run list -w 'E2E (模拟器)' -L 3)
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
check_drift
check_version
run_tests
assemble
check_jni
[ "$RELEASE" = 1 ] && check_e2e_cited

printf '\n\033[32m闸门通过\033[0m\n'
if [ "$RELEASE" != 1 ]; then
  printf '提醒:这**不等于**产物侧全查了。产物侧要的是 E2E(跑 debug 包)\n'
  printf '      与签名核对,见 docs/branching.md「四道闸门,各有各的覆盖面」。\n'
fi
