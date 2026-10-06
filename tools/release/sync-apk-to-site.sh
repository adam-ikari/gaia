#!/usr/bin/env bash
# 把私有应用仓库的 release APK 同步到公开站点仓库的同名 release。
#
# ## 为什么需要这个脚本(而不是只靠 CI)
#
# 应用仓库 `adam-ikari/hwkbd_ime` 是私有的,那里的 release 资产**匿名下不到**
# (实测 `releases/latest/download/app-release.apk` 返回 404)。站点访客要能下载,
# 包就必须同时存在于公开的 `adam-ikari/gaia`。
#
# CI 里有一个等价的 job(`release.yml` 的 `publish-site`),但它要 `WEB_RELEASE_TOKEN`:
# 跨仓库写权限只能来自 PAT,内置的 `GITHUB_TOKEN` 只对本仓库有效。
# **这个 token 目前没有配**,所以那个 job 一直 skipped。
#
# ## 两条路,各有用处
#
# 1. **本脚本**(现在这条):谁手上有 `adam-ikari/gaia` 的写权限,发完版跑一次。
#    不需要任何 secret,不会过期。发版流程里的一步,不是配置。
# 2. **CI 的 `publish-site` job**:配了 `WEB_RELEASE_TOKEN` 之后自动跑,
#    不用记着跑这个脚本。适合不想每次都手动的情况。
#
# 两条不冲突:脚本幂等,重复跑只是覆盖同名资产。
#
# 用法:
#   tools/release/sync-apk-to-site.sh v0.4.3
#   tools/release/sync-apk-to-site.sh            # 不给 tag 就用当前 master 的最新 tag
set -euo pipefail

APP_REPO=adam-ikari/hwkbd_ime
SITE_REPO=adam-ikari/gaia

TAG="${1:-}"
if [ -z "$TAG" ]; then
  TAG=$(gh release list -R "$APP_REPO" --limit 1 --json tagName -q '.[0].tagName')
fi
if [ -z "$TAG" ]; then
  echo "::error::没给 tag,且 $APP_REPO 上查不到 release" >&2
  exit 1
fi

echo "同步 $TAG:$APP_REPO → $SITE_REPO"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 从私有仓库取包(这一步需要 gh 有该仓库的读权限 —— 有 token 或有账号权限都行)
#
# 资产清单 = **远端实际存着**多少字节(权威)。
#
# 不用本地构建产物的 size —— 那是「我们以为传了什么」,而这里要验的是
# 「拿回来的东西完整吗」。两者相等是应有之义,不等才是要抓的情况。
declare -A WANT_SIZE=()
while read -r _n _s; do
  [ -n "$_n" ] && WANT_SIZE[$_n]=$_s
done < <(gh api "repos/$APP_REPO/releases/tags/$TAG" --jq '.assets[] | "\(.name) \(.size)"' 2>/dev/null || true)

if [ "${#WANT_SIZE[@]}" -eq 0 ]; then
  echo "::error::取不到 $TAG 的资产清单(仓库是私有的,需要 gh 有读权限)—— 没有权威大小就没法验完整性" >&2
  exit 1
fi

# ## 判据是**大小**,不是「文件在不在」(2026-10-05 修,真 bug)
#
# 原来判的是 `[ ! -f "$WORK/app-release.apk" ]` —— **只判存在**。
# 而 `gh release download` 的真实行为是:**整体返回非零,但已下的文件留在盘上**。
# 2026-10-05 实测(同步 v0.7.10 时):
#
#     5 次全部「失败」(退出码非零),可盘上两个文件都在:
#     app-release.apk  期望 6931568  实得 6931568  ✓
#     app-debug.apk    期望 30213158 实得 10288175 ✗ **截断到三分之一**
#
# 于是**截断的包被静默当成下载成功** —— debug 那条只发 warning,
# 没人会看;而它会被传成一个 10MB 的坏资产,并且**站点的 latest 指向它**。
#
# 讽刺的是**这与下面上传那段的注释是同一件事**:那边写着
# 「`HTTP 408` / `unexpected EOF` 反复出现(实测连续 4 次失败)」——
# **下载失败是常态,而代码假设它是例外**。
# 只判存在正好漏掉常态里最常见的那一种:下到一半、退出码非零、文件却在。
fetch() {
  local name=$1 fatal=$2 attempt got want
  want=${WANT_SIZE[$name]:-}
  if [ -z "$want" ]; then
    # 「远端根本没这个资产」≠「下载坏了」,后果也不同:
    #   致命资产缺 → 阻断(站点会停在旧版本,那是错的包);
    #   非致命资产缺 → 降级(它本来就不该有,例如 debug 包上传失败过)。
    #
    # 一律 return 1 会被 `set -e` 掐死整个脚本 —— 2026-10-05 实测正是这样
    # 卡住的:debug 包没传上去(那个 22MB 直传的老 408),于是非致命资产
    # 缺失升级成致命失败,**站点同步停在半路**,而 release 包其实已经就位。
    # 一个非致命的降级不该阻断它后面真正要做的事。
    if [ "$fatal" = yes ]; then
      echo "::error::$TAG 的资产清单里没有 $name —— 站点会停在旧版本,不许继续" >&2
      return 1
    fi
    echo "::warning::$TAG 没有 $name(远端确实没这个资产),跳过它(站点不消费它)"
    return 0
  fi
  for attempt in 1 2 3 4 5; do
    # **先删**:上一次失败留下的半截文件就在这儿,不清会被当成这次的结果。
    rm -f "$WORK/$name"
    if gh release download "$TAG" -R "$APP_REPO" -p "$name" --dir "$WORK" >/dev/null 2>&1; then
      got=$(stat -c%s "$WORK/$name" 2>/dev/null || echo 0)
      if [ "$got" = "$want" ]; then
        echo "  ✓ $name($got 字节,与远端一致)"
        return 0
      fi
      echo "  $name 第 $attempt 次:下了 $got / 期望 $want —— 截断,重下"
    else
      echo "  $name 第 $attempt 次下载失败,重试"
    fi
    sleep $((attempt * 5))
  done
  # 非致命的那条:**不留半截文件在盘上** —— 否则下面上传那段的 `-f` 判断会把它当真。
  rm -f "$WORK/$name"
  if [ "$fatal" = yes ]; then
    echo "::error::$name 连续 5 次取不到完整文件(期望 $want 字节)—— 站点会停在旧版本,不许继续" >&2
    return 1
  fi
  echo "::warning::$name 取不到完整文件,跳过它(站点不消费它)"
  return 0
}

echo "远端资产:$(for k in "${!WANT_SIZE[@]}"; do echo -n "$k=${WANT_SIZE[$k]} "; done)"
fetch app-release.apk yes || exit 1
fetch app-debug.apk no

# 幂等:同名 release 已存在就只覆盖资产,不重建(否则会把已有的说明文字冲掉)
if gh release view "$TAG" -R "$SITE_REPO" >/dev/null 2>&1; then
  echo "已存在 $SITE_REPO @ $TAG,覆盖资产"
else
  # **先建空的 release,再逐个上传**。
  #
  # 原来是把两个附件挂在 `gh release create` 的命令行上。`app-debug.apk` 有 22MB,
  # 走 S3 直传经常 `HTTP 408: Upload body timed out due to inactivity`,
  # 而 create 是**全有或全无**的 —— 一个附件失败,整个 release 都不会被建出来。
  # 症状是「同步脚本说上传超时」,而 `gh release list` 里**连这个 tag 都没有**,
  # 于是站点停在旧版本,而发版流程看上去只是「网络抖动,重试一下」。
  #
  # 拆开之后:release 先存在(页面立刻是对的),附件失败只影响附件,
  # 而且可以单独重试那一个。
  gh release create "$TAG" -R "$SITE_REPO" \
    --title "Gaia $TAG" \
    --notes "盖亚输入法 $TAG。由 $APP_REPO 的发版流水线手动同步。" >/dev/null
  echo "已在 $SITE_REPO 建 $TAG"
fi

# 逐个上传,每个最多 5 次(408 是网络/大小问题,重试通常就好)
#
# ## 两个包的**严重程度不同**(2026-10-04)
#
# 站点的 `deploy.yml` 只取 **app-release.apk** 放进 `docs/public/media/gaia.apk`,
# `Downloads.vue` 里也写明「只提供正式版」。**app-debug.apk 在站点上没有任何消费者**,
# 它只是与 app 仓库 release 的**附件对等**。
#
# 而 debug 包 22MB、release 包 6.9MB —— 差的三倍多,于是大文件那条路
# `HTTP 408: Upload body timed out due to inactivity` / `unexpected EOF`
# 反复出现(实测连续 4 次失败,而 6.9MB 的一次就过)。
#
# 所以:**release 包失败 = 致命**(站点会停在旧版本);**debug 包失败 = 警告后继续**。
# 让一个没人消费的 22MB 附带文件阻塞同步、连带 Pages 不重新部署,是不成比例的失败 ——
# 但静默跳过同样坏(下次就没人知道它没了),所以打 `::warning::`。
for f in app-release.apk app-debug.apk; do
  # 没下到的就不去上传它(否则 gh 会报一个和真实原因无关的错)
  [ -f "$WORK/$f" ] || continue
  ok=0
  for attempt in 1 2 3 4 5; do
    if gh release upload "$TAG" "$WORK/$f" -R "$SITE_REPO" --clobber >/dev/null 2>&1; then
      ok=1; break
    fi
    echo "  $f 第 $attempt 次上传失败,重试"
    sleep $((attempt * 5))
  done
  if [ "$ok" = "1" ]; then
    echo "  ✓ $f"
  elif [ "$f" = "app-release.apk" ]; then
    echo "::error::app-release.apk 连续 5 次上传失败,站点会停在旧版本 —— 不许继续" >&2
    exit 1
  else
    echo "::warning::app-debug.apk 连续 5 次上传失败(22MB 大文件)。站点不消费它(deploy.yml 只取 app-release.apk),继续。"
    echo "::warning::要对齐附件可事后补: gh release upload $TAG <apk> -R $SITE_REPO --clobber"
  fi
done

# **验一次匿名可下**。这一步不是走过场:2026-10-02 之前站点一直指向 v0.4.1,
# 而 CI 那个 job 是 skipped 状态、退出码 0 —— 流水线全绿,没人发现链接是旧的。
# 同步完不验,等于把同一个坑留到下一次。
url="https://github.com/$SITE_REPO/releases/latest/download/app-release.apk"
code=$(curl -sIL --max-time 120 -o /dev/null -w '%{http_code}' "$url")
if [ "$code" != "200" ]; then
  echo "::error::匿名下载返回 $code,不是 200 —— 同步没生效,别当成成功" >&2
  exit 1
fi
echo "✓ 匿名下载 200:$url"
echo "  (latest 落在 $(gh release list -R "$SITE_REPO" --limit 1 --json tagName -q '.[0].tagName'))"

# ---- 让站点把新包取走 ------------------------------------------------------------
#
# **release 资产与 Pages 上的那份是两个东西**(2026-10-02 差点只做一半):
#   - 上面同步的是 gaia 仓库的 **release 资产** —— `releases/latest/download/…` 那条路;
#   - 站点按钮指的 `/media/gaia.apk` 是 **Pages 的构建产物**,由站点仓库的
#     `Deploy site` 从 release 拉下来放进 dist。
# Pages 产物**只在站点仓库被 push/手动触发时才刷新**,同步 release 不会通知它。
# 所以这里显式触发一次,否则站点继续发上一次的包,而上面那步还验过 200 —— 验的
# 还是旧包。
#
# ## 触发之前先记下已有的 run(2026-10-05 加)
#
# `gh workflow run` **不返回 run id**,而「找出刚触发的那一个」不能靠
# `gh run list --limit 1` —— 那取到的是**最近一次**,若有人同时手动触发了别的
# workflow,就会盯着别人的 run 等。`docs/versioning.md` 记着同一族的坑:
# **同一个检查在两个环境跑通不代表它对**,得问「两边的初始状态一样吗」。
# 这里的初始状态就是「已有哪些 run」,所以先取一份快照,再取差集。
echo "记录 $SITE_REPO 现有的 deploy run(用于找出刚触发的那个)…"
before_runs=$(gh run list -R "$SITE_REPO" --workflow deploy.yml --limit 20 \
  --json databaseId -q '.[].databaseId' 2>/dev/null || echo "")
echo "触发站点重新部署(把新包取进 Pages)…"
if ! gh workflow run deploy.yml --repo "$SITE_REPO"; then
  echo "::error::触发站点部署失败 —— release 资产是新的,但站点页面仍是上一次的包" >&2
  exit 1
fi

# ---- 等 Deploy site 跑完,然后**验 Pages 那份** ----------------------------------
#
# ## 为什么不能像原来那样「打印一句提醒就收工」
#
# 原来脚本的最后一行是「等它跑完后,Pages 才是 $TAG」—— **提醒人去查**。
# 而 `docs/versioning.md` 里 `publish-site` 被 skipped 且退出码 0 的那几次,
# 正是这类「看起来成功」的失败模式:v0.4.2/0.4.3/0.4.4/0.4.8 站点一直停在旧版本,
# 流水线全绿、没人发现链接是旧的。提醒不算验证。
#
# 更具体的缺口(2026-10-05 发现):上面那道「匿名下载 200」验的是 **release 资产**,
# **不是 Pages 产物**。两者是两个东西 ⇒ 上传成功就能过,而站点按钮仍旧发上一次的包。
# ⇒ 所以这里必须**下 Pages 那份、逐字节比**。
pages_url="https://adam-ikari.github.io/gaia/media/gaia.apk"

# 找出刚触发的那一个 run:取差集,轮询到出现为止(CI 起来有几秒延迟)。
run_id=""
for _ in $(seq 1 20); do
  now=$(gh run list -R "$SITE_REPO" --workflow deploy.yml --limit 20 \
    --json databaseId -q '.[].databaseId' 2>/dev/null || echo "")
  for id in $now; do
    # 前后都补空格:否则 before="1 2"、id="12" 时 `*" 12 "*` 会误判成已存在
    case " $before_runs " in
      *" $id "*) ;;   # 触发前就有,跳过
      *) run_id="$id"; break ;;
    esac
  done
  [ -n "$run_id" ] && break
  sleep 3
done
if [ -z "$run_id" ]; then
  echo "::warning::没找出刚触发的 deploy run —— release 资产是新的,但站点页面是否刷新**未知**" >&2
  echo "::warning::请手工确认 $pages_url 的 versionCode" >&2
  exit 1
fi
echo "  Deploy site run: $run_id,等它跑完…"

# 等它结束。**轮询而不是 gh run watch**:watch 挂在本地断线/限流上不好收拾,
# 而这里只需要「结束了吗」这一个事实。
#
# ## 「查不到」不等于「失败」(2026-10-05 修,真 bug)
#
# 原来 conclusion 那次查询写成 `... || echo unknown`,然后
# `if [ "$concl" = "success" ]; then ok; else fail; fi` ——
# **于是一次网络抖动就等于「部署失败」**。实测:run 37391411332 其实
# `completed/success`(33 秒),而脚本判它失败并退出。
#
# 那个 `unexpected EOF` 不是假想的,同一个会话里对
# `runs/37391411332` 和 `jobs` 都真出现过。
#
# ⇒ 只有**拿到确定结论且不是 success** 才算失败;查不到就继续问。
#
# ## 更重要的是结构:这个轮询只是**便宜的路由信号**,不是判据
#
# 判据是下面那段**逐字节比对**(Pages 那份 == 刚上传的那份)。
# 原来轮询一判失败就 `exit 1`,**权威检查根本没机会跑** ——
# 于是「我猜它失败了」被当成了结论,而实际结论是可以直接问出来的。
#
# 所以:轮询失败只发 warning,继续往下走,让字节比对去判。
# 便宜的信号负责路由,贵的权威检查负责下结论。
deploy_ok=0
deploy_known=0
for _ in $(seq 1 60); do
  status=$(gh run view "$run_id" -R "$SITE_REPO" --json status -q '.status' 2>/dev/null || echo unknown)
  case "$status" in
    completed)
      concl=$(gh run view "$run_id" -R "$SITE_REPO" --json conclusion -q '.conclusion' 2>/dev/null || echo unknown)
      case "$concl" in
        success)             deploy_ok=1; deploy_known=1; break ;;
        unknown|"")          ;;  # **查不到结论 ≠ 失败**,继续问
        *)                   deploy_ok=0; deploy_known=1; break ;;
      esac ;;
    unknown|"") ;;  # 同上
    *) sleep 10 ;;
  esac
  sleep 10
done
if [ "$deploy_ok" = "1" ]; then
  echo "  Deploy site 成功"
elif [ "$deploy_known" = "1" ]; then
  echo "::warning::Deploy site 报了失败($SITE_REPO run $run_id),但结论以**下面的字节比对**为准" >&2
else
  echo "::warning::60 次都没问到 Deploy site 的结论(网络?),结论以**下面的字节比对**为准" >&2
fi

# ---- 决定性的那一步:Pages 那份必须**逐字节**等于刚上传的那份 -------------------
#
# 这是 `docs/versioning.md` 发版链路第 6 步「验:匿名下载的字节与 release 里的
# 逐字节一致」—— 之前一直靠人记得做,这里替人做。
#
# **比字节而不是比 versionCode**:字节相同 ⇒ 版本号必然相同(它就在 APK 里);
# 而只比 versionCode 会漏掉「同一个版本号配不同字节」那种(安卓会拒装升级,
# 且同一下载链接在不同时刻给出不同的包 —— 见 build.gradle.kts 里的注记)。
if [ ! -f "$WORK/app-release.apk" ]; then
  echo "::error::本地没有 app-release.apk 可比 —— 上传时就该缺了,不该到这一步" >&2
  exit 1
fi
pages_file="$WORK/pages-gaia.apk"
# 重试:Pages 产物可能比 run 完成稍晚一拍(CDN 缓存),且大文件要重下。
pages_ok=0
for attempt in 1 2 3 4 5; do
  rm -f "$pages_file"
  if curl -sL --max-time 300 -o "$pages_file" "$pages_url" && [ -s "$pages_file" ]; then
    if cmp -s "$WORK/app-release.apk" "$pages_file"; then pages_ok=1; break; fi
    echo "  第 $attempt 次下到的 Pages 包与上传的不一致,重试"
  else
    echo "  第 $attempt 次下载 Pages 包失败,重试"
  fi
  sleep $((attempt * 5))
done
if [ "$pages_ok" != "1" ]; then
  echo "::error::Pages 上的 gaia.apk 与刚上传的 app-release.apk **不一致** —— 站点发的不是这次的包" >&2
  echo "::error::期望 $pages_url == $SITE_REPO:$TAG 的 app-release.apk" >&2
  exit 1
fi
echo "✓ Pages 产物与上传的 release 包逐字节一致"

# 版本号读出来给人看(只是**报告**,不参与判定 —— 判定已经由字节比对给出)。
AAPT=$(command -v aapt2 2>/dev/null || find "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}/build-tools" -name aapt2 -type f 2>/dev/null | sort | tail -1 || true)
if [ -n "$AAPT" ]; then
  echo "  Pages 上的版本:$("$AAPT" dump badging "$pages_file" 2>/dev/null |
    sed -n "s/.*versionCode='\([^']*\)' versionName='\([^']*\)'.*/versionCode=\1 versionName=\2/p" | head -1)"
fi
echo "  $pages_url 现在是 $TAG"
