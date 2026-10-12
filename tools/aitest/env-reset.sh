#!/usr/bin/env bash
# 真机 E2E 的**环境归位**。
#
# ## 为什么需要它
#
# 2026-10-08 首日跑了十一轮,每轮都出现「这个绿了那个红了」,而**单跑与连跑
# 结果不同**。追下来发现三个红的失败点都在**各自断言之前或环境层**
# (consume-partial 在第 1 条前置「组词中输入法窗口显示」就红,压根没进断言)。
#
# ⇒ **不稳定的红全是噪声。** 先让环境确定,连跑三轮看是否稳定;
# 稳定的 10/13 才有诊断价值。
#
# ## 归位做什么(每一条都有实测证据)
#
# 1. **只留一个 Gaia 输入法** —— `dev.hwkbd.ime.dev` 与 `dev.hwkbd.ime` 并存时
#    `mInputShown=false`、按键不进 IME(`comp=-1..-1 raw=`)。实测卸载 dev 后恢复。
# 2. **IME 要先 enable 再 set** —— 否则 `Unknown input method ... cannot be selected`。
# 3. **收通知栏** —— `mCurrentFocus=NotificationShade` 时 IME 收不到触摸;
#    `cmd statusbar collapse` 不管用,实测要**下滑手势**。
# 4. **解锁 + 回前台 + 编辑框聚焦** —— 不聚焦则按键作为普通文本进 EditText。
# 5. **判布局用 Physical 不用 Override** —— `wm size` 曾处于 override 状态
#    (1436x1440@384),那是调试残留。
# 6. **清 logcat** —— 否则断言读到的是上一场景的日志。
#
# ## 判据
#
# 每一步**读回状态**确认,不只执行命令。归位成功 = 下面这些字段同时成立:
#   mInputShown=true
#   mCurrentFocus 含 PlaygroundActivity
#   敲一个 n 之后 logcat 出现 candidates[refresh]: raw="n" n≥1

set -uo pipefail

PKG=dev.hwkbd.ime
IME="$PKG/.service.ImeService"
ACT="$PKG/.playground.PlaygroundActivity"
ADB="${ADB:-adb}"
S="${AITEST_DEVICE:-}"

say()  { printf '\033[1m[env]\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m   %s\n' "$*"; }
die()  { printf '\033[31m::error::\033[0m %s\n' "$*" >&2; exit 1; }

sh_() { if [ -n "$S" ]; then "$ADB" -s "$S" shell "$@"; else "$ADB" shell "$@"; fi; }

# ---- 0. 设备 ---------------------------------------------------------------
[ -n "$(sh_ getprop ro.product.model 2>/dev/null | tr -d '\r\n')" ] || die "设备不在线"
say "设备 $(sh_ getprop ro.product.model | tr -d '\r\n')  序列号 ${S:-<默认>}"

# ---- 1. 只留一个 Gaia 输入法 --------------------------------------------------
# **不自动卸载** —— 那会丢用户数据。只报告,由人决定。
others=$(sh_ pm list packages 2>/dev/null | tr -d '\r' | grep 'hwkbd' | sed 's/package://' | grep -v "^$PKG\$" || true)
if [ -n "$others" ]; then
  warn "设备上还有其它 Gaia 渠道:$(echo "$others" | tr '\n' ' ')"
  warn "并存时 mInputShown 会是 false、按键不进 IME(2026-10-08 实测)。"
  warn "若要跑,先卸载它们:adb uninstall <包名>  ← 会丢该渠道的词频/用户数据"
  die  "为保证结果可信,这里停下。要我卸载请明确说。"
else
  ok "只有 $PKG 一个 Gaia 输入法"
fi

# ---- 2. enable + set IME ----------------------------------------------------
sh_ ime enable "$IME" >/dev/null 2>&1
sh_ ime set "$IME"    >/dev/null 2>&1
cur=$(sh_ settings get secure default_input_method 2>/dev/null | tr -d '\r\n')
[ "$cur" = "$IME" ] && ok "默认 IME = $IME" || die "设置默认 IME 失败(现在是 '$cur')"

# ---- 3. 收通知栏(靠手势,cmd statusbar collapse 实测无效) ---------------------
sh_ cmd statusbar collapse >/dev/null 2>&1
sh_ input swipe 700 100 700 1400 300 >/dev/null 2>&1
sleep 1
focus=$(sh_ dumpsys window 2>/dev/null | tr -d '\r' | grep -o 'mCurrentFocus=Window{[^}]*}' | head -1)
case "$focus" in
  *NotificationShade*) die "通知栏仍抢焦点($focus)—— 手势没生效,手动收一下" ;;
  *) ok "焦点不在通知栏:${focus:-?}" ;;
esac

# ---- 4. 解锁 + 回前台 + 聚焦编辑框 --------------------------------------------
sh_ wm dismiss-keyguard >/dev/null 2>&1
sh_ am start -n "$ACT" >/dev/null 2>&1
sleep 3
# et_playground 的 bounds 随密度变,所以从 dump 里读,不硬编码
sh_ uiautomator dump /sdcard/env.xml >/dev/null 2>&1
xml=$(sh_ cat /sdcard/env.xml 2>/dev/null | tr -d '\r')
bounds=$(printf '%s' "$xml" | grep -o 'resource-id="[^"]*et_playground[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' \
        | grep -o 'bounds="[^"]*"' | head -1 | sed 's/bounds="//;s/"//')
if [ -n "$bounds" ]; then
  nums=$(printf '%s' "$bounds" | tr -d '[]' | tr ', ' '   ')
  x1=$(printf '%s' "$nums" | cut -d' ' -f1); y1=$(printf '%s' "$nums" | cut -d' ' -f2)
  x2=$(printf '%s' "$nums" | cut -d' ' -f3); y2=$(printf '%s' "$nums" | cut -d' ' -f4)
  cx=$(( (x1 + x2) / 2 )); cy=$(( (y1 + y2) / 2 ))
  sh_ input tap "$cx" "$cy" >/dev/null 2>&1
  ok "聚焦编辑框: tap($cx,$cy)  ← bounds 从 dump 读,不硬编码"
else
  warn "读不到 et_playground 的 bounds,退回 tap(720,464)"
  sh_ input tap 720 464 >/dev/null 2>&1
fi
sleep 3

# ---- 5. 判布局用 Physical ----------------------------------------------------
# 有 override 时**停在**这里,不要带着它跑:
# 2026-10-12 实测,override 1436x1440@358 之下 app 活在逻辑屏里,候选行只能铺
# 1436,而 display-vs-commit 断言拿 Physical 1440 比 ⇒ 一条**环境造的红**
# (候选行五等分与键盘面五等分错 4px)。为结果可信,先把 override 去掉:
#   adb shell wm size reset && adb shell wm density reset
# （「candidateRow 按 Physical 算所以不受影响」这句话 2026-10-12 被证伪。）
say "屏幕 Physical $(sh_ wm size    | tr -d '\r' | grep -o 'Physical.*' )"
say "屏幕 Physical $(sh_ wm density | tr -d '\r' | grep -o 'Physical.*' )"
ov=$(sh_ wm size | tr -d '\r' | grep -c 'Override' || true)
ovd=$(sh_ wm density | tr -d '\r' | grep -c 'Override' || true)
if [ "$ov" != "0" ] || [ "$ovd" != "0" ]; then
  say "当前 override:$(sh_ wm size | tr -d '\r' | grep -o 'Override.*') / $(sh_ wm density | tr -d '\r' | grep -o 'Override.*')"
  die "存在 override —— 上面两条命令清掉再跑。它造出来的红不是产品的 bug"
fi
ok "无 override"

# ---- 6. 判据:IME 显示 + 引擎出候选 ------------------------------------------
shown=$(sh_ dumpsys input_method 2>/dev/null | tr -d '\r' | grep -o 'mInputShown=[a-z]*' | head -1)
[ "$shown" = "mInputShown=true" ] && ok "$shown" || die "$shown —— 输入法窗口没起来"

sh_ logcat -c >/dev/null 2>&1
sh_ input keyevent 42 >/dev/null 2>&1   # KEYCODE_N
sleep 2
cand=$(sh_ "logcat -d -s ImeService:* 2>/dev/null | grep -o 'candidates\[refresh\]: raw=\"n\" n=[0-9]*'" | tail -1)
case "$cand" in
  *n=[1-9]*) ok "引擎出候选: $cand" ;;
  *) die "探针失败(没出候选)。这说明 IME 没真正挂到编辑框上,或者词库还在部署。" ;;
esac

sh_ input keyevent 67 >/dev/null 2>&1   # 清掉探针打的那个字
sh_ logcat -c >/dev/null 2>&1

echo
printf '\033[32m环境已归位\033[0m —— 现在跑 E2E,连续跑三轮看是否稳定。\n'