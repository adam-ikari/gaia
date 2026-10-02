#!/usr/bin/env bash
# CI 端到端 + 素材采集,**只能在模拟器活着的那个窗口里跑**。
#
# ## 为什么必须是这个文件、而不是写在 workflow 的 `script:` 里(2026-09-30 烧了六轮才想通)
#
# 1. **emulator-runner 里模拟器的生命周期 == 那一个 step。** step 一结束它就 `emu kill`。
#    所以安装 APK、启用输入法、截图、录屏**全都要在这个 step 内部完成** ——
#    拆到后面的 step,等它们跑起来 `adb devices` 已经空了。
#    日志里的假象是"模拟器起来几十秒就没了",其实是被 action 主动拆的:
#      Starting emulator → Boot completed in 56010 ms → [group]Terminate Emulator → emu kill
#    `emu kill` 之后的 `libc++abi: terminating` 是收尾噪音,不是启动失败(我误读过一次)。
#
# 2. **workflow 的 `script:` 是用 `/usr/bin/sh` 逐行执行的**,不是当脚本文件跑。
#    所以 `for ...; do ... done` 会报 `Syntax error: end of file unexpected`,
#    `set -o pipefail` 会报 `Illegal option`(dash 没有 pipefail)。
#    ⇒ `script:` 只写一行 `bash tools/aitest/ci-capture.sh`,逻辑全在这个文件里。
#
# 环境变量(由 workflow 传入):
#   ANDROID_ADB_PATH / ADB      adb 可执行(必须显式指定:PATH 上的可能不是 action 用的那个)
#   ANDROID_SERIAL / SERIAL     设备序列号(默认 emulator-5554)
#   SHOOTS                      "1" 时采集站点素材
#   SCENES                      逗号分隔的场景名,留空则不跑
set -uo pipefail

# action 跑 script 时的工作目录不保证是仓库根,而下面的相对路径(APK、node 脚本)
# 都以根为基准。GITHUB_WORKSPACE 在 Actions 里一定有;本地手动跑时保持原样。
if [ -n "${GITHUB_WORKSPACE:-}" ] && [ -d "$GITHUB_WORKSPACE" ]; then
  cd "$GITHUB_WORKSPACE"
fi

ADB_BIN="${ANDROID_ADB_PATH:-/usr/local/lib/android/sdk/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-${SERIAL:-emulator-5554}}"
PKG=dev.hwkbd.ime
IME="$PKG/.service.ImeService"
APK=app/build/outputs/apk/debug/app-debug.apk

a() { timeout 60 "$ADB_BIN" -s "$SERIAL" "$@"; }

say() { echo "── $*"; }

say "adb=$ADB_BIN serial=$SERIAL"
a devices || true

# ---- 开机 ----
# action 自己也在等,这里只是兜底确认一次。24 × 5s = 2 分钟上限。
booted=0
for i in $(seq 1 24); do
  if [ "$(timeout 20 "$ADB_BIN" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ]; then
    booted=1; break
  fi
  sleep 5
done
if [ "$booted" != "1" ]; then
  echo "::error::boot_completed 超时"
  a shell getprop 2>&1 | head -5 || true
  exit 1
fi
say "boot completed after $i polls"

# ---- 环境 ----
# 动画全关:截图要静止帧,开着会拍到动画中间态
a shell settings put global window_animation_scale 0 || true
a shell settings put global transition_animation_scale 0 || true
a shell settings put global animator_duration_scale 0 || true
a shell wm dismiss-keyguard || true

# ---- 关掉 AOSP 的 Launcher3 ----
# 它在**正方形屏**上必崩(2026-10-01 实测,1440x1440 100% 复现):
#   com.android.launcher3.DeviceProfile.calculateHotseatBorderSpace
#   → java.lang.ArithmeticException: divide by zero
# 崩在 system_server 派发 USER_UNLOCKED 时,之后系统状态就不对,
# `am start -W` 一直回 `LaunchState: UNKNOWN (0)` / `TotalTime: 0`,Activity 起不来。
#
# **这不是 Titan 2 的行为** —— Titan 2 跑的不是 AOSP 这个 Launcher3,
# 所以这是镜像的毛病,不是我们要还原的设备特性。采集只需要一个能起 Activity 的系统,
# 不需要桌面,所以直接关掉。
a shell pm disable-user --user 0 com.android.launcher3 2>/dev/null || true
a shell cmd package set-home-activity android 2>/dev/null || true

# ---- 核对屏幕就是 Titan 2 的网格 ----
# **不要在这里 `wm size` / `wm density`。** 2026-10-01 实测:改这两个会把系统搞瘫 ——
# launcher 直接 `ArithmeticException: divide by zero` 崩掉,之后 `am start -W` 一直回
# `LaunchState: UNKNOWN (0)`,Activity 根本起不来(反复 start 十轮也不行)。
# 屏幕是在**开机前**写进 AVD 的 config.ini 的(见 e2e.yml 的「建 Titan 2 屏幕的 AVD」)。
# 这里只**核对**,对不上就报错 —— 拍出来的版式全错,比失败更糟。
say "核对屏幕: $(a shell wm size | tr '\n' ' ')| $(a shell wm density | tr '\n' ' ')"
if ! a shell wm size | grep -q '1440x1440'; then
  echo "::error::屏幕不是 1440x1440,截图版式会不对(AVD 的 hw.lcd.* 没生效?)"
  exit 1
fi
if ! a shell wm density | grep -q 'Override density: 320\|Physical density: 320'; then
  echo "::warning::密度不是 320dpi(=576dp 宽),dp 版式可能和 Titan 2 不一致"
fi

if ! timeout 300 "$ADB_BIN" -s "$SERIAL" install -r -t "$APK"; then
  echo "::error::安装失败"
  exit 1
fi

# **装完包不能立刻 `ime set`**。`adb install` 返回 Success 时,PackageManager 往往还没
# 处理完新包的 ACTION_PACKAGE_ADDED,`ime enable/set` 会回
#   Unknown input method dev.hwkbd.ime/.service.ImeService cannot be enabled for user #0
# 这是竞态不是故障(2026-10-01 在 CI 上隔一轮就复现一次:同样的脚本上一轮能选上)。
# 所以轮询等它真的出现在 `ime list -a` 里,最多 60 秒。
say "等输入法注册"
ime_ok=0
for i in $(seq 1 20); do
  if a shell ime list -s -a 2>/dev/null | grep -qx "$IME"; then ime_ok=1; break; fi
  sleep 3
done
if [ "$ime_ok" != "1" ]; then
  echo "::error::装上了包但 $IME 没注册进 ime list"
  a shell pm list packages 2>/dev/null | grep hwkbd || echo "(pm list 里没有 hwkbd)"
  exit 1
fi
say "输入法已注册($i 次轮询)"

a shell ime enable "$IME" || true
a shell ime set "$IME" || true
# 停掉模拟器自带的拉丁键盘,免得跟输入法抢
a shell ime disable com.android.inputmethod.latin 2>/dev/null || true
say "输入法:"; a shell ime list -s || true

# ---- 素材 ----
# **必须留在这个 step 里**:文件头第 1 条说过,step 一结束 action 就 `emu kill`。
# 拆到后面的 step 跑起来时 `adb devices` 已经空了。
if [ -n "${SHOOTS:-}" ]; then
  mkdir -p out/media
  say "采集素材(截图 + 录屏)"
  # 脚本用 adb -s 选设备,所以要把 env 也喂给它
  AITEST_DEVICE="$SERIAL" ADB="$ADB_BIN" timeout 900 node tools/aitest/shots.mjs out/media/shots \
    || echo "::warning::素材脚本有失败项"
  ls -la out/media out/media/shots 2>/dev/null || true
fi

# ---- 回归场景 ----
if [ -n "${SCENES:-}" ]; then
  for s in $(echo "$SCENES" | tr ',' ' '); do
    say "场景 $s"
    AITEST_DEVICE="$SERIAL" ADB="$ADB_BIN" timeout 300 node tools/aitest/harness.mjs run "$s" \
      || echo "::warning::场景 $s 失败"
  done
fi

# ---- 收尾证据 ----
mkdir -p out/evidence
cp -r tools/aitest/evidence/* out/evidence/ 2>/dev/null || true
a logcat -d -b crash > out/crash.log 2>/dev/null || true
a logcat -d -s ImeService:* > out/ime.log 2>/dev/null || true
say "crash 计数: $(grep -c FATAL out/crash.log 2>/dev/null || echo 0)"