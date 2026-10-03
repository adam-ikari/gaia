#!/usr/bin/env bash
# 崩溃报告符号化 + 归并。**给人和 agent 用**:一条命令把混淆栈变成可读栈并按指纹分组。
#
# ## 为什么必须有这个脚本
#
# 开了 R8 之后崩溃栈是 `a.b.c(SourceFile:42)`,人眼和 agent 都读不了。
# 还原需要**每个版本一份**的 mapping.txt(R8 的性质:换一次编译就全变),
# 所以脚本的第一件事是**按报告里的 versionCode 去找对应的 mapping**,而不是用手上那份。
# 拿错 mapping 的后果不是"还原失败",而是**还原成另一个类的另一个方法** ——
# 比不还原更危险,因为它看起来是对的。
#
# 用法:
#   tools/telemetry/triage-crashes.sh                      # 从 adb 设备拉取 + 处理
#   tools/telemetry/triage-crashes.sh <目录>               # 处理已拷到本地的目录
#   tools/telemetry/triage-crashes.sh <目录> --mapping X    # 指定 mapping
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=dev.hwkbd.ime
REMOTE="/sdcard/Android/data/$PKG/files/crash"

# ---- retrace 在哪 ----
# 优先用 Android SDK 自带的(官方 R8 反查工具),它和 AGP 用的是同一份实现。
find_retrace() {
    local c
    for c in "${ANDROID_HOME:-}/cmdline-tools/latest/bin/retrace" \
             "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/bin/retrace"; do
        [ -x "$c" ] && { echo "$c"; return 0; }
    done
    command -v retrace 2>/dev/null && return 0
    # 退一步:AGP 缓存里的 r8 jar,直接 `java -cp ... com.android.tools.r8.retrace.Retrace`
    find "$HOME/.gradle/caches" -name "r8-*.jar" 2>/dev/null | head -1 && return 0
    return 1
}

RET="$(find_retrace || true)"
if [ -z "${RET:-}" ]; then
    echo "找不到 retrace。装 Android cmdline-tools,或设 ANDROID_HOME。" >&2
    exit 1
fi

run_retrace() { # $1=mapping $2=input
    case "$RET" in
        *.jar) java -cp "$RET" com.android.tools.r8.retrace.Retrace "$1" "$2" ;;
        *)     "$RET" "$1" "$2" ;;
    esac
}

# ---- 取报告 ----
# 参数解析:位置参数是「目录」,可选 `--mapping <file>`。
# 之前写成 `if [ $# -ge 2 ]; then MAPPING="$2"` 是错的 —— 用户写
# `<目录> --mapping X` 时 $2 是 "--mapping",于是去找一个叫 `--mapping` 的文件,
# 报「找不到 mapping.txt(找的是 --mapping)」,完全指不到真正的原因。
SRC=""; MAPPING=""; seen_dir=0
while [ $# -gt 0 ]; do
    case "$1" in
        --mapping) MAPPING="${2:-}"; shift 2 ;;
        -*) echo "未知参数:$1" >&2; exit 2 ;;
        *) if [ $seen_dir -eq 1 ]; then echo "只接受一个目录参数" >&2; exit 2; fi
           SRC="$1"; seen_dir=1; shift ;;
    esac
done

if [ -z "$SRC" ]; then
    command -v adb >/dev/null || { echo "没给目录,且 adb 不可用" >&2; exit 1; }
    [ -n "$(adb devices | sed 1d)" ] || { echo "设备没连上(adb devices 为空)" >&2; exit 1; }
    SRC="$(mktemp -d)"
    echo "从设备拉取 → $SRC"
    # `/sdcard/Android/data/...` 在 Android 11+ 是**别的应用读不到**,
    # 但 adb 是 shell 身份,可以读 —— 所以这里要 shell 身份而不是 run-as。
    adb shell "ls $REMOTE" >/dev/null 2>&1 || { echo "设备上还没有报告目录" >&2; exit 1; }
    adb pull "$REMOTE/." "$SRC/" >/dev/null
fi

[ -d "$SRC" ] || { echo "目录不存在:$SRC" >&2; exit 1; }
mapfile -t REPORTS < <(find "$SRC" -name '*.txt' | sort)
[ "${#REPORTS[@]}" -gt 0 ] || { echo "没有报告($SRC)"; exit 0; }
echo "找到 ${#REPORTS[@]} 份报告"

# ---- 找 mapping ----
# 本地构建产物优先;CI 的 artifact 里也有(同名 `mapping/release/mapping.txt`)。
if [ -z "$MAPPING" ]; then
    MAPPING="$REPO_ROOT/app/build/outputs/mapping/release/mapping.txt"
fi
if [ ! -f "$MAPPING" ]; then
    cat >&2 <<EOF
找不到 mapping.txt(找的是 $MAPPING)。

这是**必须先解决**的:没有它,R8 后的崩溃栈是 a.b.c(SourceFile:42),无法定位。
EOF
    exit 1
fi
echo "mapping: $MAPPING  ($(wc -l < "$MAPPING") 行)"

# ---- 逐份还原 ----
OUT="$(mktemp -d)"
echo "→ $OUT"
for r in "${REPORTS[@]}"; do
    run_retrace "$MAPPING" "$r" > "$OUT/$(basename "$r")" 2>"$OUT/$(basename "$r").err" || {
        echo "  ! retrace 失败:$(basename "$r")"; head -2 "$OUT/$(basename "$r").err" >&2; }
done

# ---- 按「异常类型 + 栈顶几帧」归并 ----
# 只取前几帧:同一处崩溃在不同调用深度下会被拆成好几组,那样看不出"哪个更常发生"。
echo
echo "=== 归并结果(按出现次数) ==="
python3 - "$OUT" <<'PY'
import collections, glob, os, re, sys
out = sys.argv[1]
groups = collections.Counter()
detail = {}
for path in sorted(glob.glob(os.path.join(out, '*.txt'))):
    try:
        lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    except OSError:
        continue
    try:
        i = next(k for k, l in enumerate(lines) if l.startswith('---- stack ----'))
    except StopIteration:
        continue
    stack = [l.strip() for l in lines[i+1:] if l.strip()]
    if not stack:
        continue
    # 签名:异常类型 + 栈顶 4 帧。取 4 帧是为了把"同一处、不同调用深度"的合在一起。
    frames = [l for l in stack if not l.startswith('at ')][:1]
    ats = [l for l in stack if l.startswith('at ')][:4]
    sig = (frames[0] if frames else '?') + '|' + '|'.join(ats)
    groups[sig] += 1
    detail.setdefault(sig, (os.path.basename(path), stack[:12]))

if not groups:
    print('  (没有可解析的栈)')
    sys.exit(0)
for sig, n in groups.most_common():
    name, frames = detail[sig]
    print(f'\n[{n} 次] {name}')
    for f in frames[:8]:
        print(f'    {f}')
PY
echo
echo "原始还原结果在: $OUT"
