#!/usr/bin/env bash
#
# 构建站点并把产物推到公开仓 `adam-ikari/gaia`,Pages 从那个仓的 `main` 分支发布。
#
# ## 为什么这个脚本存在(monorepo 化,2026-10-08)
#
# 站点源码原本在公开仓 gaia 里,构建与部署由它的 `deploy.yml` 做。发一版要动两个地方:
# 应用仓打 tag → CI 出包 → `sync-apk-to-site.sh` 把包传到 gaia → 推 gaia 触发 Pages。
#
# 那条链上「站点是否与代码同步」**没有任何验证**:`release.yml` 里等价的
# `publish-site` job 要 `WEB_RELEASE_TOKEN`,而那个 secret 从来没配 ——
# job 一直 skipped、**退出码 0**、流水线全绿,而站点上的包停在旧版本。
# v0.4.2 / 0.4.3 / 0.4.4 / v0.4.8 各漏过一次(见 `docs/versioning.md` 第五步)。
#
# 结构上的成因是:能改站点的人不该看见应用源码,能改应用源码的人看不见站点。
# ⇒ 源码与构建收进本仓库一处(源码 `web/`,构建在这里),产物推公开仓,
# **判据从「我记得推了吗」变成「这个脚本的退出码」**。
#
# ## 判据是什么:公开站点上的字节 == 本地构建产物里的字节
#
# 不是「push 成功了」(push 成功与 Pages 是否重新发布是两件事),
# 不是「curl 得到 200」(200 只验存在,不验完整 —— 见下面 fetch 的注释),
# 而是**下站点那份回来逐字节比**。这是 `sync-apk-to-site.sh` 第六步那条规则,
# 搬到这里继续成立。
#
# ## 为什么推产物进 git 而不是留个构建流水线
#
# 公开仓现在只放产物,Pages 直接从 `main` 发布。
# 代价:产物(含 APK)进 git 历史,每版 +7~28MB ⇒ 仓库会单调变大。
# 这与站点仓原来的 `.gitignore` 里那句「不把 APK 提交进 git」相反,
# **是这次刻意换掉的方向**:宁可仓库大,也不要「谁在 CI 里构建、构建成功没有」
# 成为一个独立的、可能静默失败的事实。产物进 git ⇒ 线上那一份**可追溯到某个 commit**。
#
# 用法:
#   tools/release/publish-site.sh              # 构建 + 推 + 验
#   tools/release/publish-site.sh --dry-run    # 只构建,不推(打印会推什么)
#   tools/release/publish-site.sh --skip-fetch # 用 `web/docs/public/media/` 里已有的包
#
# 环境变量:
#   SITE_REPO   公开仓(默认 adam-ikari/gaia)
#   STABLE_STATUS / STABLE_NOTE   主渠道的状态与提示文字(见下面「状态是内容,不是逻辑」)

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 1
cd "$REPO_ROOT" || exit 1

SITE_REPO="${SITE_REPO:-adam-ikari/gaia}"
WEB=web
DRY=0
SKIP_FETCH=0
for a in "$@"; do
  case "$a" in
    --dry-run)    DRY=1 ;;
    --skip-fetch) SKIP_FETCH=1 ;;
    -h|--help)    sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "::error::不认识的参数 $a" >&2; exit 1 ;;
  esac
done

die() { printf '\033[31m::error:: %s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$1"; }

command -v gh >/dev/null || die "需要 gh"
command -v npm >/dev/null || die "需要 npm"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ---- 主渠道的状态:这是**内容**,所以在这里,不在 download.ts 的逻辑里 ---------
#
# 取值:
#   official   正常,可作主渠道推
#   withdrawn  有已知问题,不该当主渠道推;NOTE 会显示在按钮下面
#
# 为什么必须是**发布的人写下来**,而不是脚本从 release 属性推:
# GitHub 上没有「这个版本是经流水线发的」这种标记。v0.7.10 是在应用仓
# Actions 账单锁定时**手工**发的,而它带着一个已知缺陷。
# 「这个包可不可信」**只有发布的人知道**。
#
# 2026-10-08:仍是 withdrawn。v0.7.10 的缺陷(拼音部分消费后剩余拼音没有候选)
# 在本仓库 master 上已修(`cd04b5a`),但**没发过新版**,所以主渠道还指着它。
#
# **提示里不再指别的渠道**。原来这句尾巴是「已撤回,建议改用下面的测试版或开发版」,
# 两处错:
#   · **方向**:β/dev 在第二节,主按钮在最后一节 —— 「下面的」指的是上面。
#   · **撞名**:主渠道自己就叫「测试版」,而 β 在页面上也叫「测试版」,
#     于是这句话读成「建议改用下面的测试版」时**分不清在说谁**。
# ⇒ 渠道称呼统一由 `web/docs/.vitepress/download.ts` 那节定:只有主渠道用
#   「测试版」,β/dev 用在系统里看得到的应用名。而「修复在哪条线上」这句
#   挪到用户**正在挑构建**的那一节(`AltDownloads.vue`),不留在页面最底下。
STABLE_STATUS="${STABLE_STATUS:-withdrawn}"
# **标点用全角**。这句是直接上页面的散文,而整站正文都是全角标点;
# 半角逗号混进去(「问题:拼音被部分消费后,剩余…」)在截图里一眼看得出来。
STABLE_NOTE="${STABLE_NOTE:-0.7.10 有一个已知问题：拼音被部分消费后，剩余拼音没有候选（例如 nihao 选「你」之后 hao 的候选不出现）。}"

# ============================================================================
# 第一步:取三条渠道的 APK
# ============================================================================
#
# ## 权威大小 = **远端实际存着多少字节**
#
# 不用「本地构建产物的 size」—— 那是「我们以为传了什么」,
# 而这里要验的是「拿回来的东西完整吗」。两者相等是应有之义,不等才是要抓的情况。
#
# ## 判据是**大小**,不是「文件在不在」(2026-10-05 修,真 bug)
#
# 原来判的是 `[ ! -f ... ]` —— **只判存在**。而 `gh release download` 的真实行为是:
# **整体返回非零,但已下的文件留在盘上**。2026-10-05 实测(同步 v0.7.10 时):
#
#     5 次全部「失败」(退出码非零),可盘上两个文件都在:
#     app-release.apk  期望 6931568  实得 6931568  ✓
#     app-debug.apk    期望 30213158 实得 10288175 ✗ **截断到三分之一**
#
# 于是**截断的包被静默当成下载成功**,而站点的 latest 指向它。
# 讽刺的是这与上传那段的注释是同一件事:那里写着「HTTP 408 反复出现(实测连续 4 次失败)」
# —— **下载失败是常态,而代码假设它是例外**。
# 只判存在正好漏掉常态里最常见的那一种:下到一半、退出码非零、文件却在。
#
# ## `fatal` 只管「缺」,不管「坏」—— 这两件事后果不同
#
# **缺**:主渠道**致命**,beta/dev **非致命**。
# 照抄主渠道那句 `test -s ... || exit 1` 给 beta/dev 的话,
# 「还没发过 beta」就会让整个脚本失败 ⇒ **主渠道也更新不了、站点停在上一次**。
# 一个可选功能能弄挂生产,这是最坏的形状。
#
# **坏**(文件在、但字节不完整):本脚本里**不会**走到「跳过」那条路 ——
# 下载那步按**字节数**判完整性,取不到完整的就当没取到(重试 5 次后按 fatal 分流)。
# 所以「明知有半截文件还发出去」在这里不成立。
#
# 那为什么产物那侧还要再验一遍(`unzip` 校验 + 页面 sha256)?
# 因为它们判的**不是同一件事**:这一段判「下载时没被截断」,
# 产物那侧判「即将上线的那份文件本身是不是个能装的 APK」——
# 后者还覆盖「推产物这一步自己出了错」这种本地没察觉的情形。
# 两道判据指向不同的失效点,所以都留。

# $1=release 引用(tag),$2=资产名前缀,$3=落地路径,$4=输出变量前缀,$5=致命性
#
# **身份(hash)从资产名读**,不从 tag 推导 —— 资产名是「远端确实存着什么」,
# 是这件事的权威。改成从 tag 推导就会多出一个可能与包漂移的来源,
# 不一致的表现是「文件名说 A、装完设置页写 B」。
#
# ## 结果直接写进 shell 变量,不写 env 文件再 source
#
# 第一版是把 `KEY=value` 追加到一个文件、构建前 `.` 进来。
# 而 `STABLE_NOTE` 是**中文散文**,里面有 `(`、`「`、`,` ——
# `KEY=0.7.10 有一个已知问题:…(例如 …)` 不是合法的 shell 赋值,
# source 报 syntax error。**脚本照样往下跑**(没开 `set -e`),于是提示文字静悄悄没了,
# 页面上的「已知问题」那一行消失,而构建全绿。
#
# 这正是本项目反复付过学费的那一类失败:**报错被吞掉,后果是少说一句话**。
# ⇒ 变量直接进 shell,连 source 这一步都取消。
take_channel() {
  local label=$1 ref=$2 prefix=$3 dest=$4 var=$5 fatal=$6 id_from=${7:-asset}
  local ID_FROM="$id_from"
  local asset ver want got attempt sha bytes

  # ## 身份**永远**从远端资产名读 —— 包括「文件已在盘上」这条路
  #
  # 第一版把「文件已存在」当成完全跳过,身份改从**本地文件名**解析:
  #     ver=$(basename "$dest" .apk); ver="${ver##*-}"
  # 而落地路径是 `gaia-dev.apk` ⇒ `${ver##*-}` 得到字面量 **`dev`**。
  #
  # 后果(2026-10-08 实测):连续跑两次脚本时,第二次跳过下载,
  # 页面上的开发版身份从 `c271426` 变成 `dev` ⇒
  # 用户存下来的文件名变成 `gaia-ime-dev-dev.apk`,
  # 而**它对应哪个 commit 这件事彻底丢了** —— 那正是用户唯一能拿回来报障的东西。
  #
  # 症状很隐蔽:构建成功、APK 字节一致、页面上的 sha256 也对得上
  # (那个是从盘上文件算的,不受影响),只有文件名里的身份错了。
  #
  # 根因是「**权威来源被局部替换**」:同一件事(这个包是谁)在两条路径上
  # 用了两个来源,而其中一条更方便 ⇒ 两边迟早不一致。
  # ⇒ 身份只有远端资产名一个来源;「跳过下载」只跳过**取字节**,不跳过**问身份**。
  # 一次问出「资产名 + 权威大小」。**大小是判据**,后面拿它拦「盘上那份是旧的」。
  local want_remote
  read -r asset want_remote < <(gh release view "$ref" -R "$SITE_REPO" --json assets -q \
    "[.assets[] | select(.name | startswith(\"$prefix\"))] | last // {} | \"\(.name) \(.size)\"" 2>/dev/null || true)
  case "$want_remote" in ''|*[!0-9]*) want_remote=0 ;; esac

  if [ -s "$dest" ]; then
    # ## 盘上有包 ≠ 盘上那个包就是**这一次**要发的包(2026-10-10 修,真 bug)
    #
    # 原来这条路只「跳过下载、取身份与校验值」,**从没比过字节**。
    # 后果实测:发 v0.7.11 时 `web/docs/public/media/gaia.apk` 还留着 10-08
    # 那版(6931568 字节),于是**旧字节被贴上 0.7.11 的标签推上线** ——
    # 校验值也对,因为那是对着盘上旧文件算的。用户从「测试版 0.7.11」按钮
    # 下到的是上一版,而页面上的 sha256 自洽,整条链没有一处会报错。
    #
    # 判据与下载那条路**同源**:字节数必须等于远端该资产的字节数。
    # 不等就**当盘上没有**(删掉,走下面的下载),而不是信盘上那份。
    if [ -z "$asset" ]; then
      if [ "$fatal" = yes ]; then
        echo "::error::$label: 盘上有包,但 $SITE_REPO@$ref 上已没有 $prefix* 资产 —— 状态不自洽" >&2
        return 1
      fi
      echo "::warning::$label: 盘上有包,但远端已没有 $prefix* 资产,这个渠道本次不上线"
      return 0
    fi
    bytes=$(stat -c%s "$dest")
    if [ "$want_remote" -gt 0 ] && [ "$bytes" = "$want_remote" ]; then
      case "$ID_FROM" in
        tag)  ver="${ref#v}" ;;
        *)    ver="${asset#$prefix-}"; ver="${ver%.apk}" ;;
      esac
      sha=$(sha256sum "$dest" | cut -d' ' -f1)
      printf -v "${var}_VERSION" '%s' "$ver"
      printf -v "${var}_SHA256" '%s' "$sha"
      printf -v "${var}_SIZE"   '%s' "$bytes"
      printf -v "${var}_HAS"    '%s' 1
      ok "$label: 用盘上已有的包,身份 $ver 取自远端资产名 $asset($bytes 字节,与远端一致)"
      return 0
    fi
    echo "    $label: 盘上那份 $bytes 字节 ≠ 远端 $asset 的 $want_remote 字节 —— 盘上是旧包,重新下载"
    rm -f "$dest"
  fi

  if [ -z "$asset" ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: $SITE_REPO@$ref 上没有 $prefix* 资产 —— 发布流程断了" >&2
      return 1
    fi
    echo "::warning::$label: $ref 里没有 $prefix* 资产,这个渠道本次不上线"
    return 0
  fi

  # 主渠道的身份**从 tag 来**,不从资产名 —— 它是 `app-release.apk`,
  # 没有 hash 尾巴可剥,硬剥会得到字面量 `app-release`。
  # beta/dev 相反:身份在资产名的 hash 尾巴里,tag 只是滚动/预发的容器。
  case "$ID_FROM" in
    tag)  ver="${ref#v}" ;;
    *)    ver="${asset#$prefix-}"; ver="${ver%.apk}" ;;
  esac
  if [ -z "$ver" ] || [ "$ver" = "$asset" ] || [ "$ver" = "$prefix" ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: 资产名 $asset 里解析不出身份 —— 发布流程断了" >&2
      return 1
    fi
    echo "::warning::$label: 资产名 $asset 解析不出身份,跳过(总比给出错的强)"
    return 0
  fi

  # **权威大小**:远端那个资产的字节数。
  want=$(gh api "repos/$SITE_REPO/releases/tags/$ref" --jq \
    ".assets[] | select(.name==\"$asset\") | .size" 2>/dev/null | head -1)
  case "$want" in
    ''|*[!0-9]*) want=0 ;;
  esac

  for attempt in 1 2 3 4 5; do
    # **先删**:上一次失败留下的半截文件就在这儿,不清会被当成这次的结果。
    rm -f "$dest"
    if gh release download "$ref" -R "$SITE_REPO" -p "$asset" --dir "$WORK/dl" >/dev/null 2>&1; then
      got=$(stat -c%s "$WORK/dl/$asset" 2>/dev/null || echo 0)
      # 大小对不上就是**截断**,哪怕 gh 回 0。
      if [ "$want" -gt 0 ] && [ "$got" = "$want" ]; then
        mv "$WORK/dl/$asset" "$dest"
        break
      fi
      echo "    $label 第 $attempt 次:下了 $got / 期望 $want —— 截断,重下"
    else
      echo "    $label 第 $attempt 次下载失败,重试"
    fi
    rm -f "$WORK/dl/$asset"
    sleep $((attempt * 5))
  done
  if [ ! -s "$dest" ]; then
    rm -f "$dest"
    if [ "$fatal" = yes ]; then
      echo "::error::$label 连续 5 次取不到完整文件(期望 $want 字节) —— 不许继续" >&2
      return 1
    fi
    echo "::warning::$label 取不到完整文件,这个渠道本次不上线"
    return 0
  fi
  sha=$(sha256sum "$dest" | cut -d' ' -f1)
  bytes=$(stat -c%s "$dest")
  printf -v "${var}_VERSION" '%s' "$ver"
  printf -v "${var}_SHA256" '%s' "$sha"
  printf -v "${var}_SIZE"   '%s' "$bytes"
  printf -v "${var}_HAS"    '%s' 1
  ok "$label: $asset → ${dest#$REPO_ROOT/} (身份 $ver, $bytes 字节, sha256 ${sha:0:12}…)"
  return 0
}

if [ "$SKIP_FETCH" = 1 ]; then
  step "用 web/docs/public/media/ 里已有的包(--skip-fetch)"
  mkdir -p "$WEB/docs/public/media"
  ok "跳过取包"
else
  step "取三条渠道的 APK(来自 $SITE_REPO 的 release)"
  mkdir -p "$WORK/dl" "$WEB/docs/public/media"

  # ---- 主渠道:最新的正式 release(latest 不含 prerelease)----
  #
  # ## dev 为什么必须是 prerelease
  # `dev-latest` 一旦不是 prerelease,GitHub 就把它当成 latest ⇒
  # 主渠道那步去 `dev-latest` 里找 `app-release.apk`,找不到 ⇒ 部署失败
  # ⇒ **主渠道下载按钮整个挂掉**。2026-10-06 实测踩过。
  STABLE_TAG=$(gh release view -R "$SITE_REPO" --json tagName -q .tagName 2>/dev/null || echo "")
  [ -n "$STABLE_TAG" ] || die "取不到 $SITE_REPO 的 latest release"
  echo "    主渠道: $STABLE_TAG"
  take_channel "主渠道" "$STABLE_TAG" "app-release" "$WEB/docs/public/media/gaia.apk" "STABLE" yes tag || exit 1
  # **tag 带 v,文件名里不带**:`v0.7.11` → `0.7.11`
  APP_VERSION="${STABLE_TAG#v}"

  # ---- 测试版:挑一个**真的含有 app-beta-* 资产**的 prerelease ----
  #
  # ## 不能按「最新的 prerelease」挑
  # 因为 dev 也是 prerelease,「最新 prerelease」可能就是 `dev-latest`。
  # 判据改成「这个 release 的资产里有没有 `app-beta-*`」——
  # **按内容挑,不按命名约定挑**。这样 beta 与 dev 谁新谁旧都不影响,
  # 将来多了第四个 prerelease 渠道也不会选错。
  #
  # 2026-10-08 实测:gaia 上**现在没有任何含 app-beta-* 的 release**
  # ⇒ 测试版渠道长期缺席,页面上的测试版按钮不渲染(非致命,按设计)。
  BETA_TAG=$(gh api "repos/$SITE_REPO/releases" --paginate --jq \
    '[.[] | select(.prerelease == true)
        | {tag: .tag_name,
           hasBeta: ([.assets[].name | select(startswith("app-beta-"))] | length > 0)}]
     | map(select(.hasBeta)) | first | .tag // ""' 2>/dev/null || true)
  if [ -z "$BETA_TAG" ]; then
    echo "::warning::$SITE_REPO 上还没有含 app-beta-* 的 release,测试版本次不上线(非致命)"
  else
    echo "    测试版: $BETA_TAG"
    take_channel "测试版" "$BETA_TAG" "app-beta" "$WEB/docs/public/media/gaia-beta.apk" "BETA" no
  fi

  # ---- 开发版:固定 tag 的滚动 release ----
  # dev **不是发布**,它没有语义版本号(应用仓那边刻意不给),
  # 所以每次 master 构建都发一个 release 是刷屏 + 语义撒谎。
  # ⇒ 固定 tag `dev-latest`,资产 `--clobber` 覆盖,历史靠 git。
  echo "    开发版: dev-latest"
  take_channel "开发版" "dev-latest" "app-dev" "$WEB/docs/public/media/gaia-dev.apk" "DEV" no
fi

# ============================================================================
# 第二步:构建
# ============================================================================
step "构建站点"
[ -d "$WEB/node_modules" ] || { cd "$WEB" && npm ci && cd "$REPO_ROOT"; }

# **绝对路径**。第三步那个 subshell 会 `cd` 进产物仓的克隆,
# 而这里的相对路径在那儿不成立 —— 2026-10-08 实测:
# `cp: cannot stat 'web/docs/.vitepress/dist/.': No such file or directory`
#
# 更要紧的是那个 `cp` 失败之后脚本**继续往下走**:克隆里 `dist/` 已被
# `rm -rf` 删空,于是 `git diff --cached --quiet` 判成「逐字节相同」⇒
# 报「产物无变化,跳过 push」⇒ **exit 3(成功)**。
# 而线上那份其实还是旧的 —— 一个**成功退出的假成功**。
#
# 三处一起修:路径取绝对、复制失败必须致命、「无变化」只能在**确实复制过**
# 的前提下判。
BUILD_DIR="$REPO_ROOT/$WEB/docs/.vitepress/dist"
rm -rf "$BUILD_DIR"
# `VITE_*` 只在**构建期**注入,产物里是编译好的值 —— 静态站点没有运行时。
# 变量清单与 `web/docs/.vitepress/download.ts` 顶部的表一一对应。
#
# `--skip-fetch` 时取包那步整个跳过,所以这些变量可能根本没被赋过值;
# `${X:-}` 给的是空串,而 download.ts 的 `usable()` 会把空串挡掉
# (拼出 `gaia-ime-v.apk` 那种「看起来像成功了」的文件名)。
export VITE_APP_VERSION="${APP_VERSION:-}"
export VITE_STABLE_STATUS="${STABLE_STATUS:-withdrawn}"
export VITE_STABLE_NOTE="${STABLE_NOTE:-}"
export VITE_STABLE_SHA256="${STABLE_SHA256:-}"
export VITE_STABLE_SIZE="${STABLE_SIZE:-}"
export VITE_BETA_VERSION="${BETA_VERSION:-}"
export VITE_BETA_SHA256="${BETA_SHA256:-}"
export VITE_BETA_SIZE="${BETA_SIZE:-}"
export VITE_HAS_BETA="${BETA_HAS:-}"
export VITE_DEV_VERSION="${DEV_VERSION:-}"
export VITE_DEV_SHA256="${DEV_SHA256:-}"
export VITE_DEV_SIZE="${DEV_SIZE:-}"
export VITE_HAS_DEV="${DEV_HAS:-}"

( cd "$WEB" && npm run build ) > "$WORK/build.log" 2>&1 \
  || { tail -40 "$WORK/build.log" >&2; die "构建挂了(完整日志 $WORK/build.log)"; }
ok "构建完成"

# ---- 产物里必须有主渠道 APK:这是**致命**的 ----
[ -s "$BUILD_DIR/media/gaia.apk" ] \
  || die "构建产物里没有 media/gaia.apk,下载链接会 404"
ok "产物里有 media/gaia.apk"

# beta/dev 在产物里有没有**不致命** —— 它们本来就可能没上线。
# 这里只报事实,让「按钮指向 404」这件事在日志里看得见。
for f in gaia-beta.apk gaia-dev.apk; do
  if [ -s "$BUILD_DIR/media/$f" ]; then ok "产物里有 $f"
  else echo "    无 $f(该渠道本次未上线,对应按钮不渲染)"; fi
done

# **产物里的 sha256 必须等于源文件的** —— 页面上给用户的就是这个数。
# 构建会不会顺手改 APK?不会,但这一行是「页面上写的校验值对应哪些字节」的唯一权威,
# 而用户正靠它分辨「下全了没有」(2026-10-06 用户下载后装不上,只得到「软件包无效」)。
for pair in "gaia.apk:STABLE" "gaia-beta.apk:BETA" "gaia-dev.apk:DEV"; do
  f="${pair%%:*}"; var="${pair##*:}"
  [ -s "$BUILD_DIR/media/$f" ] || continue
  src="$WEB/docs/public/media/$f"
  [ -s "$src" ] || die "产物里有 $f 但源文件没了 —— 判据不能自证"
  cmp -s "$src" "$BUILD_DIR/media/$f" || die "产物里的 $f 与源文件不一致"
  eval "want=\${${var}_SHA256:-}"
  got=$(sha256sum "$BUILD_DIR/media/$f" | cut -d' ' -f1)
  [ -z "$want" ] || [ "$want" = "$got" ] \
    || die "产物里 $f 的 sha256 与页面要显示的对不上"
done
ok "产物里的 APK 与源文件逐字节一致,校验值自洽"

# ============================================================================
# 第三步:推产物
# ============================================================================
step "推产物到 $SITE_REPO"

if [ "$DRY" = 1 ]; then
  echo "    (--dry-run,不推)"
  echo "    会推到 $SITE_REPO 的 dist/:"
  ( cd "$BUILD_DIR" && find . -type f | sort | sed 's|^\./|      dist/|' )
  exit 0
fi

# 产物进 git 的 `dist/` 子目录(不是仓根)。三个理由:
#   1. 仓根要留给 `.github/`(部署 workflow)与 `README.md`(这个仓是什么),
#      它们不是站点的一部分,混进产物就会被 Pages 一并发出去。
#   2. workflow 的 `upload-pages-artifact: path: dist` 与之一致。
#   3. 产物仓根如果就是站点,`git clone` 下来的人会以为那是可编辑的站点源码 ——
#      而它其实一个字节都不能改(改了就没有对应的构建了)。
#
# **用 clone 而不是 worktree**:产物仓只有一个分支、只放产物,没有并行编辑,
# worktree 的并发假设用不上。也不在原地改 —— 那个仓跟本仓库的 git 历史无关,
# 混用 index 只会让人看不懂状态。
SITE_WORK="$WORK/site"
gh repo clone "$SITE_REPO" "$SITE_WORK" -- --depth=1 >/dev/null 2>&1 \
  || die "克隆 $SITE_REPO 失败"
(
  cd "$SITE_WORK" || exit 1
  # **只重写 dist/,不动仓根。**
  #
  # 旧结构(源码在仓根、Pages 从仓根发布)已在 monorepo 化那一次清掉了。
  # 这一版刻意**不再**清仓根:仓根现在有属于它的东西(`README.md` 说明这个仓
  # 是什么、`.github/workflows/deploy.yml` 是部署流水线)。
  # 「顺手把仓根也清一遍」正是那种将来会误删东西的写法 ——
  # 清空逻辑应该跟着它清理的那次迁移走一次,不该永久留在脚本里。
  rm -rf dist
  mkdir -p dist
  # **复制失败必须致命。** `set -e` 在 subshell 里是继承的,
  # 但这一步曾经失败而脚本继续跑完并 exit 0(见上面 BUILD_DIR 那段注释),
  # 所以不靠继承、显式判。
  cp -a "$BUILD_DIR"/. dist/ || { echo "::error::复制产物失败" >&2; exit 4; }
  # 复制之后再确认产物真的在那儿 —— 「命令回 0」不等于「文件在」。
  [ -s dist/index.html ] || { echo "::error::dist/index.html 复制后不存在" >&2; exit 4; }
  [ -s dist/media/gaia.apk ] || { echo "::error::dist/media/gaia.apk 复制后不存在" >&2; exit 4; }
  git add -A
  if git diff --cached --quiet; then
    echo "    产物与远端**逐字节相同** —— 这次没有任何要更新的东西"
    exit 3
  fi
  git -c user.name="$(git -C "$REPO_ROOT" config user.name || echo adam)" \
      -c user.email="$(git -C "$REPO_ROOT" config user.email || echo adam@users.noreply.github.com)" \
      commit -q -m "产物:$(git -C "$REPO_ROOT" rev-parse --short HEAD)

站点源码与构建在私有仓 adam-ikari/hwkbd_ime 的 web/,由
tools/release/publish-site.sh 生成。本仓只放产物。"
  git push -q origin HEAD:main
) || rc=$?
rc=${rc:-0}
case "$rc" in
  3) echo "    (产物无变化,跳过 push)" ;;
  4) die "复制产物失败 —— 公开站点上的仍是上一次的产物,而本地构建是新的" ;;
  0) ;;
  *) die "推产物失败 —— 公开站点上的仍是上一次的产物,而本地构建是新的" ;;
esac

# ============================================================================
# 第四步:验 —— 公开站点上的字节 == 本地产物里的字节
# ============================================================================
step "验公开站点"
#
# Pages 从分支发布,**push 成功与站点已更新是两件事**:要先等 Pages 重新构建,
# 而它构建失败时只在仓库的 Pages 设置里留一行字,推的人看不到。
#
# CDN 还会缓存 ⇒ 刚推完立刻 curl 拿到的是**上一版**。所以下面轮询到字节对上为止。
#
# 验的**不是「页面 200」**:200 只验存在,不验完整。
# 2026-10-06 实测这个网络下下载会静默截断(有一次连文件都没落下来,页面本身却正常),
# 用户拿到残缺的 APK,安装器只给一句「软件包无效」。
# ⇒ 必须下回来逐字节比。这是唯一能把「装不上」归因清楚的判据。
SITE_URL="https://adam-ikari.github.io/gaia"
verify_one() {
  local path=$1 label=$2 attempt
  local want_sha want_size got_sha
  want_sha=$(sha256sum "$BUILD_DIR/$path" | cut -d' ' -f1)
  want_size=$(stat -c%s "$BUILD_DIR/$path")
  for attempt in 1 2 3 4 5 6 7 8; do
    rm -f "$WORK/dl-verify"
    if curl -sL --max-time 300 -o "$WORK/dl-verify" "$SITE_URL/$path" 2>/dev/null \
       && [ -s "$WORK/dl-verify" ]; then
      got_sha=$(sha256sum "$WORK/dl-verify" | cut -d' ' -f1)
      if [ "$got_sha" = "$want_sha" ]; then
        ok "$label: $path 与本地产物逐字节一致($want_size 字节)"
        return 0
      fi
      got_size=$(stat -c%s "$WORK/dl-verify")
      echo "    第 $attempt 次:$label 站点上那份($got_size 字节)与本地产物($want_size 字节)不同 —— Pages 还没更新,重试"
    else
      echo "    第 $attempt 次:下载 $label 失败,重试"
    fi
    sleep $((attempt * 10))
  done
  return 1
}

verify_one "media/gaia.apk" "主渠道" || die "主渠道 APK 在站点上与本地产物不一致 —— 这次更新没生效,别当成成功"
if [ -s "$BUILD_DIR/media/gaia-dev.apk" ]; then
  verify_one "media/gaia-dev.apk" "开发版" || die "开发版 APK 与本地产物不一致"
fi
if [ -s "$BUILD_DIR/media/gaia-beta.apk" ]; then
  verify_one "media/gaia-beta.apk" "测试版" || die "测试版 APK 与本地产物不一致"
fi

# 页面本身也要看一眼:产物目录对不等于**页面引用的路径**对。
# 「dist 里有 gaia.apk」与「页面按钮指向 /media/gaia.apk」是两个独立事实,
# 而后者错了的症状是 404 页(VitePress 把 .apk 当网页路由接管,2026-10-02 真机报障)。
HTML_SHA=$(sha256sum "$BUILD_DIR/index.html" | cut -d' ' -f1)
for attempt in 1 2 3 4 5 6 7 8; do
  rm -f "$WORK/dl-index"
  if curl -sL --max-time 120 -o "$WORK/dl-index" "$SITE_URL/" 2>/dev/null \
     && [ -s "$WORK/dl-index" ]; then
    got=$(sha256sum "$WORK/dl-index" | cut -d' ' -f1)
    if [ "$got" = "$HTML_SHA" ]; then
      ok "首页与本地产物一致"
      break
    fi
    if [ "$attempt" = 8 ]; then
      die "首页在站点上与本地产物不一致 —— 部署没生效"
    fi
    echo "    第 $attempt 次:首页还不是本次产物,重试"
  elif [ "$attempt" = 8 ]; then
    die "首页取不到"
  fi
  sleep $((attempt * 10))
done

echo
printf '\033[32m✓ 站点已更新\033[0m %s\n' "$SITE_URL/"
echo "  主渠道 $STABLE_TAG(状态 $STABLE_STATUS)"
[ -n "${BETA_TAG:-}" ] && echo "  测试版 $BETA_TAG" || echo "  测试版 本次未上线(gaia 上没有 app-beta-* 资产)"
[ -n "${DEV_VERSION:-}" ] && echo "  开发版 dev-$DEV_VERSION" || echo "  开发版 本次未上线"