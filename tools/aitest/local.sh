#!/usr/bin/env bash
#
# 本地真机 E2E 入口 —— **回归以后只在这里跑**(2026-10-06 起)。
#
# ## 为什么从 CI 搬回本地
#
# 三条理由,按硬度排序:
#
# 1. **私有仓的 Actions 是计费的,而账单已经欠费。** CI 上的 E2E 一天烧掉
#    119 分钟(2026-10-05 实测 11 次),换来的却是 2 秒失败、零检查的假红灯。
# 2. **它本来就不是发布闸门。** E2E 与 release 在 `on: push: tags` 上**并行、
#    无依赖** ⇒ E2E 红了 release 照样绿。它是发版前的**人工前置**,
#    而人工前置跑在远端没有意义 —— 该跑的人看不到,看到了也改不动。
# 3. **CI 上跑的是模拟器,本地跑的是真机。** 模拟器验不到的东西
#    (真实屏幕密度、Titan 2 的物理键盘、真实 IMU/触摸时序)本地反而能验。
#
# ## 为什么不是「本地模拟器」
#
# 试过了,不行(2026-10-05 实测,不是推测):
#
#     Process system isn't responding
#     System UI isn't responding
#
# 没有 `/dev/kvm` 时软件渲染下 **SystemUI 自己 ANR**,输入法窗口根本没机会显示
# (`mInputShown=false`),按键全被系统当普通文本吃掉。
# 还踩了一个更具体的坑:`adb shell` **执行成功但进程不返回**,Node 的
# `spawnSync` 直接 `ETIMEDOUT` —— 日志里明明有输出,harness 却读到超时。
#
# ⇒ 本机只能跑**真机**。这个脚本因此把「是不是模拟器」变成一条会拦人的判据。
#
# ## 用法
#
#     tools/aitest/local.sh                        # 全套 15 个场景
#     tools/aitest/local.sh consume-partial        # 单个场景
#     tools/aitest/local.sh list                   # 列出场景
#     tools/aitest/local.sh all --shots out/media  # 顺便采站点素材(原 e2e.yml 的 SHOOTS)
#
# 多设备时用 `AITEST_DEVICE=<序列号>` 指定。`adb devices` 里第一列是序列号,
# `adb -s <序列号> shell getprop ro.product.model` 可以确认是哪台。

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 1
cd "$REPO_ROOT" || exit 1

die() { printf '\033[31m::error:: %s\033[0m\n' "$1" >&2; exit 1; }
warn() { printf '\033[33m!\033[0m %s\n' "$1"; }

# ---- adb 定位 --------------------------------------------------------------
adb_path() {
  if [ -n "${ADB:-}" ]; then echo "$ADB"; return; fi
  for c in "$ANDROID_HOME/platform-tools/adb" "$ANDROID_SDK_ROOT/platform-tools/adb" \
           "$HOME/Android/Sdk/platform-tools/adb" "$HOME/android-sdk/platform-tools/adb"; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  command -v adb 2>/dev/null || true
}
ADB_BIN=$(adb_path)
if [ -z "$ADB_BIN" ] || [ ! -x "$ADB_BIN" ]; then
  die "找不到 adb。设置 ADB=/path/to/adb,或把 platform-tools 加进 PATH"
fi

# ---- 设备 ------------------------------------------------------------------
# `adb devices` 的第二列是状态;`offline` 的那台还在启动,列出来只会误导。
mapfile -t LINES < <("$ADB_BIN" devices | tail -n +2 | awk '$2=="device"{print $1}')
case "${#LINES[@]}" in
  0) die "没有在线设备。插上手机、打开 USB 调试、确认「允许 USB 调试」弹窗。" ;;
  1) ;;
  *) [ -z "${AITEST_DEVICE:-}" ] && die "有多台设备(${LINES[*]}),用 AITEST_DEVICE=<序列号> 指定哪一台。" ;;
esac
DEV="${AITEST_DEVICE:-${LINES[0]}}"
SERIAL="$DEV"
export ADB="$ADB_BIN"
export AITEST_DEVICE="$SERIAL"

# ---- 这是不是模拟器 --------------------------------------------------------
#
# 判据用 `ro.kernel.qemu` / `ro.hardware`,**不用序列号形态** ——
# `emulator-5554` 只是默认命名,`adb tcpip` 连上来的真机也可能长那样;
# 反过来 CI 里手动命名的模拟器也未必。属性是系统自己说的。
is_emu=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')
hw=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.hardware 2>/dev/null | tr -d '\r\n')
model=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
sdk=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')

printf '\033[1m本地真机 E2E\033[0m\n'
printf '  设备   %s\n' "$SERIAL"
printf '  型号   %s\n' "${model:-?}"
printf '  Android API %s\n' "${sdk:-?}"

if [ "$is_emu" = "1" ] || [[ "$hw" == goldfish* || "$hw" == ranchu* ]]; then
  die "这是**模拟器**($model / ro.hardware=$hw),不是真机。
     本机没有 /dev/kvm,软件渲染下 SystemUI 自己 ANR,输入法窗口都显示不了
     —— 2026-10-05 实测过,不是推测。E2E 请在真机上跑。"
fi

if [ -z "$model" ] || [ "$model" = "?" ]; then
  warn "读不到 ro.product.model,不能确认这是不是真机。继续,但请自己核对。"
fi

# ---- 会在真机上做的事,说在前面 -------------------------------------------
#
# 这不是「只读」测试。它会:
#   · `adb install -r` 装 app-debug.apk(**签名与正式包一致,可覆盖,不丢数据**)
#   · 切默认输入法到 dev.hwkbd.ime,跑完**不切回去**
#   · 在 `PlaygroundActivity`(本项目自己的界面)里打字,不碰别的应用
#
# 所以「跑完记得把输入法切回去」这件事得自己记得,或者现在记下原值:
cur=$("$ADB_BIN" -s "$SERIAL" shell settings get secure default_input_method 2>/dev/null | tr -d '\r\n')
printf '  当前默认输入法 %s\n' "${cur:-?}"
[ "$cur" != "dev.hwkbd.ime/.service.ImeService" ] && \
  printf '  (跑完它会被切成 Gaia;原值记在这里: %s)\n' "$cur"

# ---- 跑 --------------------------------------------------------------------
#
# `--shots <目录>` 额外采站点素材 —— 原先那是 `e2e.yml` 的 `SHOOTS` 输入做的事
# (`ci-capture.sh` 里调 `shots.mjs`)。CI 那条流水线删掉后,素材采集搬到这里,
# 所以「删 CI」不等于「砍功能」。不传这个开关就只跑回归。
SHOTS_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --shots) SHOTS_DIR="${2:-out/media/shots}"; shift 2 ;;
    *) break ;;
  esac
done

run_shots() {
  local dir=$1
  mkdir -p "$dir" || die "建不了 $dir"
  printf '\n\033[1m采站点素材\033[0m → %s\n' "$dir"
  # `AITEST_DEVICE`/`ADB` 已经 export 过了,shots.mjs 用同一套约定。
  node tools/aitest/shots.mjs "$dir" || warn "素材采集失败(不影响回归结果)"
  printf '  产出:\n'
  find "$dir" -maxdepth 1 -type f -printf '    %10s  %f\n' 2>/dev/null
}

echo
case "${1:-all}" in
  list) node tools/aitest/harness.mjs list; exit $? ;;
  all|"") node tools/aitest/harness.mjs run all; rc=$?
          [ -n "$SHOTS_DIR" ] && run_shots "$SHOTS_DIR"
          exit $rc ;;
  *)     node tools/aitest/harness.mjs run "$1"; rc=$?
          [ -n "$SHOTS_DIR" ] && run_shots "$SHOTS_DIR"
          exit $rc ;;
esac
