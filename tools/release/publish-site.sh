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
#
# 站点只发**两条渠道**:测试版 β(主下载)与开发版 dev。2026-10-23 起不再有
# 正式版主渠道 —— 这个项目从来没有正式版,原来的主渠道只是一份挂着「已撤回」
# 状态的旧包,留着比删掉更容易误导。

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

# 版本号从 APK 的 manifest 读 —— 那是装完之后在设置页看到的同一个字符串
# (`0.7.11-beta.a2f69d8`),而不是从 tag 或资产名拼出来的。
# 与「身份从资产名读」不冲突:身份仍是 hash,版本号是给人看的。
AAPT2="${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0/aapt2"
[ -x "$AAPT2" ] || AAPT2=$(command -v aapt2 2>/dev/null || true)

apk_version_name() {
  [ -n "$AAPT2" ] && [ -x "$AAPT2" ] || return 1
  "$AAPT2" dump badging "$1" 2>/dev/null \
    | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ============================================================================
# 第一步:取两条渠道的 APK
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
# **缺**:测试版**致命**,开发版**非致命**。
# 测试版现在是站点的主下载,首页按钮直接指向它 —— 取不到它,按钮就是 404。
# 开发版可选:取不到就不渲染按钮;一个可选渠道不能弄挂整个发布,
# 否则「这次没有 dev 包」会连测试版也更新不了,站点停在上一次。
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
# 而注入值里一旦有中文散文,`(`, `「`, `,` 就不是合法的 shell 赋值,
# source 报 syntax error,脚本却照样往下跑(没开 `set -e`)——
# 文字静悄悄没了,而构建全绿。
#
# 这正是本项目反复付过学费的那一类失败:**报错被吞掉,后果是少说一句话**。
# ⇒ 变量直接进 shell,连 source 这一步都取消。
take_channel() {
  local label=$1 ref=$2 prefix=$3 dest=$4 var=$5 fatal=$6
  local asset ver want got attempt sha bytes out q_ok

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
  # 一次问出「资产名」。**没有「权威大小」可用** —— GitHub 的资产 API
  # 只给 name/size,而 size 比不了内容(见下面 2026-10-11 的第三次教训),
  # 所以身份只有远端资产名一个来源,字节只有下载回来这一个来源。
  #
  # ## 这个查询也要**重试**,并且区分「查不到」与「没这东西」(2026-10-11 修)
  #
  # 原来是 `read -r asset < <(gh release view … 2>/dev/null || true)`:
  # 网络抖一下,`|| true` 把失败吞掉、asset 为空,脚本便断定
  # 「远端没有 app-release* 资产」并**致命退出** —— 实测 2026-10-11:
  # v0.7.11 的 app-release.apk 好好在那儿,一次抖动就让整个发布中断。
  #
  # 与下面大小查询同一个形状(**查询失败 ≠ 结果为空**,今天第二次):
  #   · gh 退出码非零 ⇒ 查不到,重试;5 次都查不到 ⇒ **不知道**,按 fatal 分流;
  #   · 退出码 0 而输出为空 ⇒ 真的没有匹配资产(beta 渠道常年如此,非致命)。
  # 两条路在原来的写法里一模一样 —— 都是 asset 为空。
  asset=""
  q_ok=0
  for attempt in 1 2 3 4 5; do
    if out=$(gh release view "$ref" -R "$SITE_REPO" --json assets -q \
        "[.assets[] | select(.name | startswith(\"$prefix\"))] | last | .name // \"\"" 2>/dev/null); then
      asset=$out
      q_ok=1
      break
    fi
    sleep $((attempt * 3))
  done
  if [ "$q_ok" = 0 ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: 连续 5 次取不到 $ref 的资产清单 —— 无从判断有没有 $prefix* 资产,不许继续" >&2
      return 1
    fi
    echo "::warning::$label: 连续 5 次取不到 $ref 的资产清单,这个渠道本次不上线"
    return 0
  fi

  # ## 「盘上已有包」这条捷径已删掉(2026-10-11 实测,同一个形状的第三次)
  #
  # 它原本为省一次 10~22MB 的下载:盘上有包、字节数与远端资产相等就复用。
  # 2026-10-10 刚把这里的判据从「文件在不在」改成「字节数等不等」,
  # 理由是对的(截断的包也是文件)。而字节数**同样**不是内容判据:
  # 两个 APK 可以一样大。
  #
  # 实测(发 9582d85):b9b34d8 与 9582d85 只差一个 shell 脚本,
  # versionName 后缀 `b9b34d8`→`9582d85` 等长 ⇒ 两个包都是 10699199 字节。
  # 盘上那份旧包于是通过字节数检查被复用,页面显示 `dev 9582d85`
  # 配的 sha256 却是上一版的字节 —— 旧内容贴新标签推上线,整条链无一处报错。
  # (连脚本最后的「线上与本地产物逐字节一致」也是真的:那个「本地产物」
  #  就是从盘上旧包复制来的。判据链每一环都自洽,只是没碰到真正的包。)
  #
  # GitHub 的 release 资产 API **不提供 sha256**(只有 name/size),
  # 所以「盘上那份 == 远端那份」不把字节拿回来就无从判断。
  # ⇒ 捷径删掉:每次都下载,大小校验通过后从**下载到的字节**算 sha256。
  #   想用盘上的包,有显式的 `--skip-fetch` —— 那是发布的人明确选的路。
  #
  # 与 2026-10-10 那处修法不矛盾:那次把「存在」升级成「大小」是对的,
  # 这一次是承认大小到头了。判据要比的是**字节**,而只有字节能比字节。

  if [ -z "$asset" ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: $SITE_REPO@$ref 上没有 $prefix* 资产 —— 发布流程断了" >&2
      return 1
    fi
    echo "::warning::$label: $ref 里没有 $prefix* 资产,这个渠道本次不上线"
    return 0
  fi

  # 身份在资产名的 hash 尾巴里,tag 只是预发/滚动的容器。
  ver="${asset#$prefix-}"; ver="${ver%.apk}"
  if [ -z "$ver" ] || [ "$ver" = "$asset" ] || [ "$ver" = "$prefix" ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: 资产名 $asset 里解析不出身份 —— 发布流程断了" >&2
      return 1
    fi
    echo "::warning::$label: 资产名 $asset 解析不出身份,跳过(总比给出错的强)"
    return 0
  fi

  # **权威大小**:远端那个资产的字节数。
  #
  # ## 这个查询也要**重试**,而且拿不到就是拿不到(2026-10-11 实测,假失败)
  #
  # 第一版只查一次:`gh api` 抖一下(`unexpected EOF` 在这个网络下是常态)返回空,
  # `case` 把空串归一成 `want=0` —— 于是下面那句
  #     [ "$want" -gt 0 ] && [ "$got" = "$want" ]
  # 的**大小校验被静默禁用**:每一次下载其实都完整成功(实测 5 次全是
  # 10699195 字节),却每次都因为「和 0 比不上」被判成截断,5 次之后
  # 非致命渠道放弃上线。而**下载重试了、查询没有** —— 重试只保住了
  # 不重要的那一半,判据的那一半裸奔。
  #
  # 与 publish-dev.sh 的 `remote_asset_size()` / `dev_assets()` 同一形状:
  # **查询失败 ≠ 结果为空**,要用退出码/重试区分,不能归一成 0 假装「没这东西」。
  #
  # 拿不到权威大小时**不许放行**:没有它就无从判断截断
  # (2026-10-05 的教训:gh 退出码非零而文件还在盘上,半截文件是常态)。
  # ⇒ 重试 5 次仍拿不到,按 fatal 分流,与「取不到完整文件」同等对待。
  want=0
  for attempt in 1 2 3 4 5; do
    q=$(gh api "repos/$SITE_REPO/releases/tags/$ref" --jq \
      ".assets[] | select(.name==\"$asset\") | .size" 2>/dev/null | head -1)
    case "$q" in
      ''|*[!0-9]*) sleep $((attempt * 3)) ;;
      *) want=$q; break ;;
    esac
  done
  if [ "$want" = 0 ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: 连续 5 次取不到 $asset 的权威大小 —— 没有它无法判断下载是否完整,不许继续" >&2
      return 1
    fi
    echo "::warning::$label: 取不到权威大小,无法判断下载完整性,这个渠道本次不上线"
    return 0
  fi

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
  step "取两条渠道的 APK(来自 $SITE_REPO 的 release)"
  mkdir -p "$WORK/dl" "$WEB/docs/public/media"

  # 旧主渠道的包如果还留在源目录,VitePress 会把它原样复制进产物 ——
  # 页面上没有按钮,文件却仍然能下载。删掉它,「站点不再提供正式版」才是真的。
  rm -f "$WEB/docs/public/media/gaia.apk"

  # ---- 测试版:挑一个**真的含有 app-beta-* 资产**的 prerelease ----
  #
  # ## 不能按「最新的 prerelease」挑
  # 因为 dev 也是 prerelease,「最新 prerelease」可能就是 `dev-latest`。
  # 判据改成「这个 release 的资产里有没有 `app-beta-*`」——
  # **按内容挑,不按命名约定挑**。这样 beta 与 dev 谁新谁旧都不影响。
  #
  # ## 查询失败 ≠ 没有 beta
  # `gh api` 抖一下返回空,不能据此断言「gaia 上没有 beta 资产」——
  # 那个断言会让发布停下,而真正的故障在网络上。重试 5 次,仍然取不到
  # 就是**不知道**,按致命处理并说清楚;取到了空结果才是真的没有。
  BETA_TAG=""
  q_ok=0
  for attempt in 1 2 3 4 5; do
    if BETA_TAG=$(gh api "repos/$SITE_REPO/releases" --paginate --jq \
      '[.[] | select(.prerelease == true)
          | {tag: .tag_name,
             hasBeta: ([.assets[].name | select(startswith("app-beta-"))] | length > 0)}]
       | map(select(.hasBeta)) | first | .tag // ""' 2>/dev/null); then
      q_ok=1
      break
    fi
    sleep $((attempt * 3))
  done
  [ "$q_ok" = 1 ] || die "连续 5 次取不到 $SITE_REPO 的 release 清单 —— 这**不代表没有 beta**,
     先自己核对一次:gh release list -R $SITE_REPO"
  [ -n "$BETA_TAG" ] || die "$SITE_REPO 上还没有含 app-beta-* 的 release ——
     测试版是站点的主下载,取不到它,首页按钮就是 404,不许发布"
  echo "    测试版: $BETA_TAG"
  take_channel "测试版" "$BETA_TAG" "app-beta" "$WEB/docs/public/media/gaia-beta.apk" "BETA" yes || exit 1

  # ---- 开发版:固定 tag 的滚动 release ----
  # dev **不是发布**,它没有语义版本号(应用仓那边刻意不给),
  # 所以每次 master 构建都发一个 release 是刷屏 + 语义撒谎。
  # ⇒ 固定 tag `dev-latest`,资产 `--clobber` 覆盖,历史靠 git。
  echo "    开发版: dev-latest"
  take_channel "开发版" "dev-latest" "app-dev" "$WEB/docs/public/media/gaia-dev.apk" "DEV" no
fi

# ---- 版本号:从落地那个 APK 里读,不拼 ----
#
# 资产名只有 hash(`app-beta-a2f69d8.apk`),而用户要知道的是版本号
# (`0.7.11-beta.a2f69d8`)—— 这也是装完在设置页看到的字符串。
# 站点既然能拿到包,就以包里的 manifest 为准;从资产名或 tag 拼是又一个来源。
#
# 测试版读不出就**不许发**:按钮上没版本号正是这次要修的问题。
# 开发版可选,读不出就留空,页面退回显示 hash。
BETA_VERSION_NAME=""
DEV_VERSION_NAME=""
if [ -s "$WEB/docs/public/media/gaia-beta.apk" ]; then
  BETA_VERSION_NAME=$(apk_version_name "$WEB/docs/public/media/gaia-beta.apk") || true
  [ -n "$BETA_VERSION_NAME" ] \
    || die "读不出测试版 APK 的 versionName(aapt2: ${AAPT2:-未找到})—— 站点要显示版本号,不许发布"
  if [ -n "${BETA_VERSION:-}" ]; then
    case "$BETA_VERSION_NAME" in
      *"$BETA_VERSION"*) : ;;
      *) die "测试版 versionName($BETA_VERSION_NAME)里没有资产身份 $BETA_VERSION —— 包和资产名对不上" ;;
    esac
  fi
  ok "测试版版本号: $BETA_VERSION_NAME"
fi
if [ -s "$WEB/docs/public/media/gaia-dev.apk" ]; then
  DEV_VERSION_NAME=$(apk_version_name "$WEB/docs/public/media/gaia-dev.apk") || true
  if [ -z "$DEV_VERSION_NAME" ]; then
    echo "::warning::读不出开发版 APK 的 versionName,按钮退回显示 hash"
  elif [ -n "${DEV_VERSION:-}" ]; then
    case "$DEV_VERSION_NAME" in
      *"$DEV_VERSION"*) ok "开发版版本号: $DEV_VERSION_NAME" ;;
      *)
        DEV_VERSION_NAME=""
        echo "::warning::开发版 versionName 与资产身份 $DEV_VERSION 对不上,按钮退回显示 hash"
        ;;
    esac
  fi
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
# `--skip-fetch` 走的是另一条路:上面那段 `rm` 不会执行,旧的 gaia.apk
# 可能还躺在 public/media 里。这里再删一次,保证它不进任何一次构建。
rm -f "$WEB/docs/public/media/gaia.apk"
# `VITE_*` 只在**构建期**注入,产物里是编译好的值 —— 静态站点没有运行时。
# 变量清单与 `web/docs/.vitepress/download.ts` 顶部的表一一对应。
#
# `--skip-fetch` 时取包那步整个跳过,所以这些变量可能根本没被赋过值;
# `${X:-}` 给的是空串,而 download.ts 的 `usable()` 会把空串挡掉
# (拼出 `gaia-ime-beta-.apk` 那种「看起来像成功了」的文件名)。
export VITE_BETA_VERSION="${BETA_VERSION:-}"
export VITE_BETA_VERSION_NAME="${BETA_VERSION_NAME:-}"
export VITE_BETA_SHA256="${BETA_SHA256:-}"
export VITE_BETA_SIZE="${BETA_SIZE:-}"
export VITE_DEV_VERSION="${DEV_VERSION:-}"
export VITE_DEV_VERSION_NAME="${DEV_VERSION_NAME:-}"
export VITE_DEV_SHA256="${DEV_SHA256:-}"
export VITE_DEV_SIZE="${DEV_SIZE:-}"
export VITE_HAS_DEV="${DEV_HAS:-}"

( cd "$WEB" && npm run build ) > "$WORK/build.log" 2>&1 \
  || { tail -40 "$WORK/build.log" >&2; die "构建挂了(完整日志 $WORK/build.log)"; }
ok "构建完成"

# ---- 产物里必须有测试版 APK:这是**致命**的 ----
[ -s "$BUILD_DIR/media/gaia-beta.apk" ] \
  || die "构建产物里没有 media/gaia-beta.apk,主下载链接会 404"
ok "产物里有 media/gaia-beta.apk"

# 开发版在产物里有没有**不致命** —— 它本来就可能没上线。
# 这里只报事实,让「按钮指向 404」这件事在日志里看得见。
if [ -s "$BUILD_DIR/media/gaia-dev.apk" ]; then ok "产物里有 gaia-dev.apk"
else echo "    无 gaia-dev.apk(该渠道本次未上线,对应按钮不渲染)"; fi

# **产物里的 sha256 必须等于源文件的** —— 页面上给用户的就是这个数。
# 构建会不会顺手改 APK?不会,但这一行是「页面上写的校验值对应哪些字节」的唯一权威,
# 而用户正靠它分辨「下全了没有」(2026-10-06 用户下载后装不上,只得到「软件包无效」)。
for pair in "gaia-beta.apk:BETA" "gaia-dev.apk:DEV"; do
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

# ---- 页面上必须出现版本号 ----
#
# 「按钮上显示版本号」是这次的要求。注入了却没渲染出来(改动标签时漏了字段),
# 构建会成功、上面的字节校验也全对 —— 只有看页面才发现。⇒ 按字节抓一次。
if [ -n "${BETA_VERSION_NAME:-}" ]; then
  grep -qF -- "$BETA_VERSION_NAME" "$BUILD_DIR/index.html" \
    || die "页面里没有测试版版本号 $BETA_VERSION_NAME —— 版本号没渲染出来"
  ok "页面里显示了测试版版本号: $BETA_VERSION_NAME"
fi

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
  [ -s dist/media/gaia-beta.apk ] || { echo "::error::dist/media/gaia-beta.apk 复制后不存在" >&2; exit 4; }
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

verify_one "media/gaia-beta.apk" "测试版" || die "测试版 APK 在站点上与本地产物不一致 —— 这次更新没生效,别当成成功"
if [ -s "$BUILD_DIR/media/gaia-dev.apk" ]; then
  verify_one "media/gaia-dev.apk" "开发版" || die "开发版 APK 与本地产物不一致"
fi

# 页面本身也要看一眼:产物目录对不等于**页面引用的路径**对。
# 「dist 里有 gaia-beta.apk」与「页面按钮指向 /media/gaia-beta.apk」是两个独立事实,
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
echo "  测试版 $BETA_TAG"
[ -n "${DEV_VERSION:-}" ] && echo "  开发版 dev-$DEV_VERSION" || echo "  开发版 本次未上线"