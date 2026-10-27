#!/usr/bin/env bash
#
# 构建站点产物(页面 + 两条渠道的 APK),交给**本仓**的 GitHub Pages。
# 由 `.github/workflows/deploy-site.yml` 调用:这个脚本只管「构建 + 自检 + 验线上」,
# `upload-pages-artifact` / `deploy-pages` 两步在 workflow 里。
#
# ## 判据是什么:公开站点上的字节 == 本地构建产物里的字节
#
# 不是「workflow 绿了」(部署 Pages 与上传产物是两个动作),
# 不是「curl 得到 200」(200 只验存在,不验完整 —— 见下面 fetch 的注释),
# 而是**下站点那份回来逐字节比**。这条从 `sync-apk-to-site.sh` 时代搬过来,一直成立。
#
# 2026-10-06 实测过这个网络下下载**会静默截断**:装不上的包只给一句「软件包无效」。
# 所以最后这一步不是可选的确认,是整条链唯一能分清「包坏了」与「我网坏了」的判据。
#
# ## 2026-10-27:产物不再进任何 git 仓
#
# 原来的形状是「源码在本仓 `web/`,构建在这里,产物推到公开仓 gaia 的 `dist/`」,
# 那是 2026-10-08 的 monorepo 第一段 —— 它解决的是「站点是否与代码同步**没有任何验证**」
# (`release.yml` 里等价的 publish-site job 要一个从来没配过的 secret,于是永远 skipped
# 且退出码 0,流水线全绿而站点停在旧版本,v0.4.2/0.4.3/0.4.4/0.4.8 各漏过一次)。
#
# 现在公开仓要删掉,产物改由 workflow 直接上传 Pages。这**不是妥协,是删掉一层**:
#   · 「线上那份可追溯到哪个 commit」这件事原来靠往 git 里提交产物来保证,
#     现在由「哪个 run 部署的」保证 —— 而 run 本来就绑在 commit 上,且它带完整日志;
#   · 提交产物那个动作原本还要防「推上去但 Pages 没更新」(push 成功与 Pages 发布是两件事),
#     现在这两件事在同一次 run 里,不再有中间状态;
#   · 那个仓存在的另一半理由(部署侧要有个地方验产物自洽 + 拿本仓 token 部署 Pages)
#     整个搬进 deploy-site.yml,判据一条不丢。
#
# ## 包为什么从 release 资产取,而不是直接用刚编出来的那份
#
# GitHub 对**单个文件**有服务端硬上限 100.00MB(十进制):离线语音模型进 APK 后
# beta 是 103,060,018 B、dev 是 130,072,818 B,`git push` 会被 pre-receive 直接拒
# (`GH001`)。实测用一个 110MB 稀疏文件,远端 4.1 秒后拒收 —— 与机器、带宽、HTTPS/SSH
# 都无关,换 runner 也换不掉。**Pages artifact 没有这个单文件上限**,233MB 实测可上传
# (2026-10-26 在 gaia 上用一次性分支探过,结论记在那次 run 里)。
#
# 所以包的**权威存放地**是 release 资产(上限 2GB/个),站点上的 `/media/*.apk`
# 是部署时按「远端实际存着多少字节」取回来的那份。这样站点显示的 sha256 对应的是
# **用户真正会拿到的字节**,而不是「我们以为编出来的字节」。
#
# ## 为什么这一步必须在 runner 上跑
#
# 开发机到 GitHub 的上传,**单个 HTTP 请求持续 ~20 秒就被 408 掐断**(2026-10-26 实测:
# 12MB 的 `git push` 在 20.5 秒失败、2MB 通过;`gh release upload` 3MB 通过、10MB 超时)。
# β(≈99MB)+ dev(≈124MB)不是一个能切小的请求,git 也无法续传。
# ⇒ 构建与部署都在 CI;这个脚本在本地只剩「改文案后看一眼产物长什么样」有用。
#
# 用法:
#   tools/release/publish-site.sh              # 取包 + 构建 + 自检(产物留在 dist/,等 workflow 上传)
#   tools/release/publish-site.sh --verify     # 只验:线上那份字节 == dist/ 里的字节
#   tools/release/publish-site.sh --skip-fetch # 不联网的 HTML 冒烟构建(**不产 media/,这种产物不可发布**)
#
# 环境变量:
#   GITHUB_REPOSITORY   CI 里的当前仓;本机则由 `gh repo view` 顺 origin 解析
#
# 站点只发**两条渠道**:测试版 β(主下载)与开发版 dev。2026-10-23 起不再有
# 正式版主渠道 —— 这个项目从来没有正式版,原来的主渠道只是一份挂着「已撤回」
# 状态的旧包,留着比删掉更容易误导。

set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd) || exit 1
cd "$REPO_ROOT" || exit 1

WEB=web
SKIP_FETCH=0
VERIFY_ONLY=0
for a in "$@"; do
  case "$a" in
    --verify)     VERIFY_ONLY=1 ;;
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

# ---- 目标仓、站点地址、base:三件事都由**当前仓名**推出来 --------------------
#
# 原来这三处各写着一个字面量 `gaia`。这个仓要改名,而字面量在改名之后不会报错:
#   · 目标仓写死 ⇒ 包发进一个已经不存在的仓(或别人的仓),站点取到旧字节;
#   · 站点地址写死 ⇒ 最后那条「下回线上那份逐字节比」的判据在**验另一个站**;
#   · VitePress 的 base 写死 ⇒ 页面能出、样式与 chunk 全 404。
# ⇒ 只留一个来源:CI 里的 `GITHUB_REPOSITORY`,本机则由 `gh repo view` 顺 origin 解析。
#   两者都跟着 remote 走,改名后自动就是新名字。
REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)}"
[ -n "$REPO" ] || die "解析不出目标仓:CI 里没有 GITHUB_REPOSITORY,本机 gh repo view 也失败(origin 指向 GitHub 吗?)"
REPO_NAME="${REPO##*/}"
# 项目站的 URL 与 base 都是 `/<仓名>/` —— 同一个事实的两面,所以从同一个变量派生。
SITE_URL="https://${REPO%/*}.github.io/${REPO_NAME}"
SITE_BASE="/${REPO_NAME}/"
export SITE_BASE

# 产物目录。CI 里构建步与 `--verify` 步之间靠它传递(同一个 job 的 workspace)。
BUILD_DIR="$REPO_ROOT/$WEB/docs/.vitepress/dist"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ============================================================================
# 判据:公开站点上的字节 == 产物里的字节
# ============================================================================
#
# 验的**不是「页面 200」**:200 只验存在,不验完整。
# 2026-10-06 实测这个网络下下载会静默截断(有一次连文件都没落下来,页面本身却正常),
# 用户拿到残缺的 APK,安装器只给一句「软件包无效」。
# ⇒ 必须下回来逐字节比。这是唯一能把「装不上」归因清楚的判据。
#
# 「workflow 绿了」也不等于「站点更新了」:`deploy-pages` 成功与 Pages 的 CDN 生效之间
# 还有一段。所以下面轮询到字节对上为止,并说清去哪儿看那次 run。
verify_one() {
  local path=$1 label=$2 ref=$3 attempt got_size
  local want_sha want_size got_sha
  want_sha=$(sha256sum "$ref" | cut -d' ' -f1)
  want_size=$(stat -c%s "$ref")
  for attempt in $(seq 1 12); do
    rm -f "$WORK/dl-verify"
    if curl -sL --max-time 300 -o "$WORK/dl-verify" "$SITE_URL/$path" 2>/dev/null \
       && [ -s "$WORK/dl-verify" ]; then
      got_sha=$(sha256sum "$WORK/dl-verify" | cut -d' ' -f1)
      if [ "$got_sha" = "$want_sha" ]; then
        ok "$label: $path 与本次产物逐字节一致($want_size 字节,第 $attempt 次)"
        return 0
      fi
      got_size=$(stat -c%s "$WORK/dl-verify")
      echo "    第 $attempt 次:$label 站点上那份($got_size 字节)与本次产物($want_size 字节)不同 —— 部署还没生效,重试"
    else
      echo "    第 $attempt 次:下载 $label 失败,重试"
    fi
    sleep $((attempt * 15))
  done
  echo "::error::$label 在 20 分钟窗口内没等到 —— 先看本仓的部署 run:"
  echo "        gh run list -R $REPO --workflow deploy-site.yml --limit 3"
  echo "        取包失败/大小或 sha256 对不上都会让那次 run 红,而红的时候 Pages 仍是上一版。"
  return 1
}

verify_live() {
  # 期望字节来自**产物**:`dist/media/*.apk` 就是 workflow 刚上传给 Pages 的那份,
  # 拿它当参照,判的才是「上传的那串字节有没有原样回来」。
  # 这里不再回读 release 资产 —— 那是构建时(`take_channel`)另一条判据的事。
  if [ ! -s "$BUILD_DIR/media/gaia-beta.apk" ]; then
    echo "    产物里没有测试版的包($BUILD_DIR/media/gaia-beta.apk)—— 这种产物本来就没上传过,验无可验"
    return 1
  fi
  verify_one "media/gaia-beta.apk" "测试版" "$BUILD_DIR/media/gaia-beta.apk" \
    || die "测试版 APK 在站点上与本次产物不一致 —— 这次更新没生效,别当成成功"
  if [ -s "$BUILD_DIR/media/gaia-dev.apk" ]; then
    verify_one "media/gaia-dev.apk" "开发版" "$BUILD_DIR/media/gaia-dev.apk" \
      || die "开发版 APK 与本次产物不一致"
  fi

  # 页面本身也要看一眼:产物目录对不等于**页面引用的路径**对。
  # 「dist 里有 gaia-beta.apk」与「页面按钮指向 /media/gaia-beta.apk」是两个独立事实,
  # 而后者错了的症状是 404 页(VitePress 把 .apk 当网页路由接管,2026-10-02 真机报障)。
  local HTML_SHA got attempt
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
  printf '\033[32m✓ 站点已更新\033[0m %s/\n' "$SITE_URL"
}

# `--verify`:部署那两步(upload-pages-artifact / deploy-pages)在 workflow 里,
# 这一步是紧跟在其后的判据。分开是因为「上传并部署」与「线上生效」之间要等。
#
# ## `|| die` 不是格式问题,是这个脚本最重要的一行
#
# 本机实测(2026-10-27):`verify_live` 在「产物里根本没有 media/」那条路上是
# `echo` + `return 1`,而这里原来写的是裸 `verify_live` ⇒ 函数说了「验无可验」,
# 脚本却 `exit 0`。**报错被吞掉,后果是看起来成功了** —— 这条链上每一处事故都是这个形状
# (`publish-site` job 缺 secret 时 skipped 且退出码 0;`cp` 失败后照样判「无变化」)。
# 所以:凡是拿退出码当判据的地方,都要显式收回来。
if [ "$VERIFY_ONLY" = 1 ]; then
  step "验公开站点($SITE_URL)"
  verify_live || die "线上那份与本次产物不一致 —— 这次更新没生效,别当成成功"
  exit 0
fi

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
    if out=$(gh release view "$ref" -R "$REPO" --json assets -q \
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
  #   `--skip-fetch` 不是「用盘上的包」那条路 —— 它是「这次不显示包信息、也不推」的
  #   冒烟构建(2026-10-26:包不再进产物,复用本地包既省不了什么,又正好是上面那个事故)。
  #
  # 与 2026-10-10 那处修法不矛盾:那次把「存在」升级成「大小」是对的,
  # 这一次是承认大小到头了。判据要比的是**字节**,而只有字节能比字节。

  if [ -z "$asset" ]; then
    if [ "$fatal" = yes ]; then
      echo "::error::$label: $REPO@$ref 上没有 $prefix* 资产 —— 发布流程断了" >&2
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
    q=$(gh api "repos/$REPO/releases/tags/$ref" --jq \
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
    if gh release download "$ref" -R "$REPO" -p "$asset" --dir "$WORK/dl" >/dev/null 2>&1; then
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
  # manifest 要按这两个值去远端取包(deploy-site.yml 干的),所以**远端坐标**
  # 也得记下来:ref 是「资产实际躺在哪个 release 里」(beta 是按内容挑出来的那个,
  # 不是滚动 tag),asset 是资产全名。少记一个,部署侧就只能靠命名约定猜 ——
  # 而「按命名约定挑渠道」正是这条链上被教训过的那件事。
  printf -v "${var}_REF"    '%s' "$ref"
  printf -v "${var}_ASSET"  '%s' "$asset"
  ok "$label: $asset → ${dest#$REPO_ROOT/} (身份 $ver, $bytes 字节, sha256 ${sha:0:12}…)"
  return 0
}

# 两条渠道的包先落在 `$WORK/pkg`(临时),构建完再复制进产物的 `media/`。
#
# ## 为什么不放在 `web/docs/public/` 里让 VitePress 复制
#
# 那个目录是**版本库的一部分**。而 GitHub 对单个文件有服务端硬上限 100.00MB(十进制):
# 离线语音模型进 APK 之后 beta 是 103,060,018 B、dev 是 130,072,818 B,两个都超,
# `git push` 会被 pre-receive 直接拒(`GH001`)。实测一个 110MB 稀疏文件 4.1 秒被拒 ——
# 与带宽、机器、HTTPS/SSH 都无关,换 runner 也换不掉。
#
# **Pages artifact 没有这个单文件上限**(只限已发布站点 ≤ 1GB),实测 233MB 可以走
# (2026-10-26 用一次性分支在 runner 上探过)。所以包由这个脚本在构建后复制进产物,
# 再由 workflow 整个目录交给 Pages —— 字节只经过「release → runner → Pages」这条内存路径,
# 全程不进 git。
PKG_DIR="$WORK/pkg"
mkdir -p "$PKG_DIR"

if [ "$SKIP_FETCH" = 1 ]; then
  step "不联系远端(--skip-fetch):HTML 冒烟构建"
  # 这个模式**结构上不能发布**:页面上显示的 sha256、版本号、以及 manifest,
  # 全部来自远端那份包的字节。不取包就没有它们 ——
  # 而「旧包贴新标签」在这条链上真出过事故(2026-10-11:两个 versionName 等长的包
  # 字节数也相等,盘上那份旧包通过了大小检查,上线的是旧内容配新校验值,全链无一处报错)。
  # 所以这里不做「复用盘上已有的包」这种省钱的事:那需要一个可信的本地→远端身份判据,
  # 而 GitHub 的资产 API 不给校验值。
  # ⇒ 产物里没有 media/ 与 manifest,workflow 那个「验产物里有包」的步骤会把它拦下来;
  #    这个模式只用于**本地看效果**,不能部署。
  ok "跳过取包(这种产物不可部署:没有 media/,也没有校验值)"
else
  step "取两条渠道的 APK(来自 $REPO 的 release)"
  mkdir -p "$WORK/dl"

  # `public/media` 是旧结构留下的目录。产物里的包只应来自 `$PKG_DIR` 这一条路,
  # 那里出现任何 APK 都会被 VitePress 复制进 dist 并静悄悄上线,所以整个目录删掉,
  # 而不是「删 gaia.apk 那一个」。
  rm -rf "$WEB/docs/public/media"

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
    if BETA_TAG=$(gh api "repos/$REPO/releases" --paginate --jq \
      '[.[] | select(.prerelease == true)
          | {tag: .tag_name,
             hasBeta: ([.assets[].name | select(startswith("app-beta-"))] | length > 0)}]
       | map(select(.hasBeta)) | first | .tag // ""' 2>/dev/null); then
      q_ok=1
      break
    fi
    sleep $((attempt * 3))
  done
  [ "$q_ok" = 1 ] || die "连续 5 次取不到 $REPO 的 release 清单 —— 这**不代表没有 beta**,
     先自己核对一次:gh release list -R $REPO"
  [ -n "$BETA_TAG" ] || die "$REPO 上还没有含 app-beta-* 的 release ——
     测试版是站点的主下载,取不到它,首页按钮就是 404,不许发布"
  echo "    测试版: $BETA_TAG"
  take_channel "测试版" "$BETA_TAG" "app-beta" "$PKG_DIR/gaia-beta.apk" "BETA" yes || exit 1

  # ---- 开发版:固定 tag 的滚动 release ----
  # dev **不是发布**,它没有语义版本号(应用仓那边刻意不给),
  # 所以每次 master 构建都发一个 release 是刷屏 + 语义撒谎。
  # ⇒ 固定 tag `dev-latest`,资产 `--clobber` 覆盖,历史靠 git。
  echo "    开发版: dev-latest"
  take_channel "开发版" "dev-latest" "app-dev" "$PKG_DIR/gaia-dev.apk" "DEV" no
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
if [ -s "$PKG_DIR/gaia-beta.apk" ]; then
  BETA_VERSION_NAME=$(apk_version_name "$PKG_DIR/gaia-beta.apk") || true
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
if [ -s "$PKG_DIR/gaia-dev.apk" ]; then
  DEV_VERSION_NAME=$(apk_version_name "$PKG_DIR/gaia-dev.apk") || true
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

# 产物目录在文件开头就定了(`BUILD_DIR`),因为 `--verify` 模式也要用它。
#
# ## 为什么必须是绝对路径
#
# 2026-10-08 实测:相对路径在任何 `cd` 之后都不成立,而那次复制失败**脚本照样跑完** ——
# `cp` 报错,产物是空的,后面的「逐字节相同」判定于是报「无变化,跳过」并以成功退出。
# 一个**成功退出的假成功**。所以路径取绝对,复制失败一律致命。
rm -rf "$BUILD_DIR"

# ## manifest:线上那份字节「是谁、来自哪个 commit」的公开答案
#
# ## 为什么产物不进 git 之后还要留它
#
# 原来「线上可追溯到某个 commit」这件事是靠**产物提交进产物仓**成立的 —— 每个字节
# 都能对到一个 commit。改成 workflow 直接上传 Pages 之后,那个载体没了:产物存在
# Actions 的一次 run 里,而 run 日志人不会去翻。manifest 补的就是这个洞 ——
# 它随站点一起发布,任何人 `/ _artifact-manifest.json` 就能看到页面上那个版本号
# 对应哪个 release、哪个资产、多少字节、什么 sha256、哪个 commit。
#
# `--verify` 还会**回读线上那份 manifest** 比对 `source_commit`(见第三步),
# 所以它不是一段自说自话的元数据,是那条链上可判的一环。
#
# 里面每件事都是**判据**,不是注释:
#   · `release` / `asset` —— 站点上那个包是从哪个 release 的哪个资产取来的;
#   · `size` / `sha256`  —— 那份字节是不是页面写给用户看的那一份
#     (GitHub 的资产**可以被替换**;没有这两个值,「部署成功」只说明拿到过某个包);
#   · `target`           —— 它在站点上的路径(页面的链接是固定的 `/media/gaia-beta.apk`,
#     版本进 `download` 属性而不是 URL,见 brain 的 site-repo-topology);
#   · `source_commit`    —— 页面显示的版本号对应哪个 commit(runner 检出的就是它)。
#
# 写在 `public/` 里而不是构建后再塞:少一个「构建之后再动产物」的步骤就少一处出错的地方。
MANIFEST="$WEB/docs/public/_artifact-manifest.json"
rm -f "$MANIFEST"
if [ "${BETA_HAS:-}" = 1 ]; then
  SITE_COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD)
  {
    printf '{\n  "schema": 2,\n'
    printf '  "repo": "%s",\n' "$REPO"
    printf '  "source_commit": "%s",\n' "$SITE_COMMIT"
    printf '  "channels": {\n'
    printf '    "beta": {"release": "%s", "asset": "%s", "target": "media/gaia-beta.apk", "version": "%s", "size": %s, "sha256": "%s"}' \
      "$BETA_REF" "$BETA_ASSET" "$BETA_VERSION" "$BETA_SIZE" "$BETA_SHA256"
    if [ "${DEV_HAS:-}" = 1 ]; then
      printf ',\n    "dev": {"release": "%s", "asset": "%s", "target": "media/gaia-dev.apk", "version": "%s", "size": %s, "sha256": "%s"}' \
        "$DEV_REF" "$DEV_ASSET" "$DEV_VERSION" "$DEV_SIZE" "$DEV_SHA256"
    fi
    printf '\n  }\n}\n'
  } > "$MANIFEST" || die "写 manifest 失败"
  # JSON 得真的能被解析 —— 手拼的字符串最容易在「某个值恰好带引号」时碎掉,
  # 而碎掉的 manifest 症状是部署失败,离出错的地方很远。
  if command -v python3 >/dev/null; then
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$MANIFEST" \
      || die "manifest 不是合法 JSON: $MANIFEST"
  fi
  ok "manifest: beta=$BETA_ASSET(${BETA_SIZE}B)dev=${DEV_ASSET:-无}"
else
  echo "    无 manifest(--skip-fetch 的冒烟构建,产物不可发布)"
fi

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

# ---- 产物自洽:页面上写给用户看的数字,必须对应产物里的那串字节 ----
#
# 「缺」与「坏」是两件事,致命性不同。这三条判据原来在 gaia 的 `deploy.yml` 里,
# 产物不再进那个仓之后,它们跟着构建一起回到这里 —— 判据一条没少,只是换了地方。
#
# ## 缺可选渠道不 fatal,坏一律 fatal
# dev 没上线只是「那个按钮不渲染」,不该让测试版也更新不了(站点停在上一版)。
# 而「存在但截断」是**明知故犯地发一份坏字节**:用户点下去只会得到「软件包无效」,
# 正是 2026-10-06 那次报障的症状。不对称不是按渠道重要性分的,是按「这字节要不要发出去」分的。
#
# ## 完整性为什么必须用 `unzip -t` + 中央目录里的条目
# 截断的 APK 有个特点:`unzip -l` 照样能列出前面一部分条目,所以「清单里有
# AndroidManifest.xml」这一条**单独会漏掉它**。实测 3MB/6.6MB 的截断包里关键条目数
# 是 **0**(zip 的中央目录在文件末尾,截断后整个没了)⇒ 关键条目那条抓得住,
# 退出码也要看:`unzip -t` 对截断包回 **9**,完好的回 0。
#
# ## 清单必须**先落文件再 grep**,不能 `unzip -l | grep -q`
# `grep -q` 找到第一个匹配就退出 ⇒ 上游 `unzip` 收到 SIGPIPE(141),而本脚本开着
# `pipefail` ⇒ 整条管道退出码变成 141,于是「清单里有 AndroidManifest.xml」被判成
# 「没有」。2026-10-08 拿真实产物实测过这个假失败。**闸门对正确的东西报失败**是
# 最坏的一类 bug:人只会绕过它或关掉它,于是真正该拦的截断包也跟着过去。
# ⇒ 一次落盘,再 grep 文件:退出码归 grep,不牵连 unzip。
check_apk_sane() {
  local f=$1 name
  name=$(basename "$f")
  unzip -tqq "$f" >/dev/null 2>&1 \
    || { echo "::error::$name 不是完整的 APK(unzip 校验失败)—— 用户会看到「软件包无效」" >&2; return 1; }
  unzip -l "$f" > "$WORK/apk-list.txt" 2>/dev/null
  grep -q 'AndroidManifest\.xml' "$WORK/apk-list.txt" \
    || { echo "::error::$name 里没有 AndroidManifest.xml —— 它不是个能装的 APK" >&2; return 1; }
  grep -qE 'classes[0-9]*\.dex' "$WORK/apk-list.txt" \
    || { echo "::error::$name 里没有 dex —— 它不是个能装的 APK" >&2; return 1; }
  rm -f "$WORK/apk-list.txt"
  ok "$name 是完整的 APK($(stat -c%s "$f") 字节)"
}

if [ "${BETA_HAS:-}" != 1 ]; then
  echo "    (--skip-fetch 的冒烟构建:跳过产物自洽检查,这种产物不可部署)"
else
  [ -s "$BUILD_DIR/_artifact-manifest.json" ] \
    || die "产物里没有 _artifact-manifest.json —— 线上那份字节无从追溯"
  cmp -s "$MANIFEST" "$BUILD_DIR/_artifact-manifest.json" \
    || die "产物里的 manifest 与构建前写的那份不一致 —— 构建步骤动了它?"
  ok "产物里有 manifest,且与构建前逐字节一致"

  # 包**构建之后**才塞进产物,所以 VitePress 不会「顺手」把它们当页面资源处理,
  # 而 `.apk` 出现在 `public/` 里会被那条 rm -rf 删掉 —— 只有一条路能让包上线。
  mkdir -p "$BUILD_DIR/media"
  for pair in "gaia-beta.apk:BETA" "gaia-dev.apk:DEV"; do
    f="${pair%%:*}"; var="${pair##*:}"
    [ -s "$PKG_DIR/$f" ] || continue
    cp "$PKG_DIR/$f" "$BUILD_DIR/media/$f" \
      || die "把 $f 复制进产物失败 —— 站点上那个按钮会 404"
    [ -s "$BUILD_DIR/media/$f" ] || die "$f 复制后在产物里不存在(命令回 0 不等于文件在)"
    check_apk_sane "$BUILD_DIR/media/$f" || die "$f 不完整 —— 不部署"

    # 页面上写的校验值,必须就是这份包字节的校验值。
    # 这一步不是自证:比较的对象是**已构建出来的静态资源**(用户浏览器会拿到的东西),
    # 而包字节是刚下载并核对过大小的那份。中间任何环节注入错(改字段时漏了渠道、
    # 拼成 `gaia-ime-beta-.apk` 那类),这里就会断。
    #
    # 注意搜的是 `*.html` 与 `*.js` 而**不是**只看 index.html,也不是「整个 dist 里搜一遍」:
    # 校验值是客户端渲染的,它在 `assets/chunks/theme.*.js` 里而不在首页 HTML 里
    # (2026-10-26 实测,按 index.html 判会误报「数字没渲染出来」);
    # 而整个 dist 里搜会连 manifest 自己也算进去 —— 那是我们从同一个变量写出来的文件,
    # 搜它等于让判据自证。
    eval "want=\${${var}_SHA256:-}"
    got=$(sha256sum "$BUILD_DIR/media/$f" | cut -d' ' -f1)
    [ -n "$want" ] && [ "$want" = "$got" ] \
      || die "$f:注入页面的 sha256 与包字节对不上"
    hit=$(find "$BUILD_DIR" \( -name '*.html' -o -name '*.js' \) ! -path "$BUILD_DIR/media/*" \
            -exec grep -lF -- "$want" {} + | head -1)
    [ -n "$hit" ] \
      || die "静态资源里没有 $f 的校验值 ${want:0:12}… —— 数字没渲染出来,用户无从核对"
  done
  ok "页面的校验值与产物里的包字节一致"

  # Pages 的上限是**已发布站点 ≤ 1GB**(与 git 那个 100MB 单文件上限是两回事)。
  # 两条渠道现在都在产物里,合计 ~233MB,离上限还远 —— 但「远」不是判据,留一条实测的线:
  total=$(du -sk "$BUILD_DIR" | cut -f1)
  [ "$total" -lt 900000 ] \
    || die "产物合计 ${total}KB,超过 Pages 的 1GB 上限余量 —— 部署会失败,先查是谁把包塞大了"
  ok "产物合计 $((total / 1024))MB(Pages 上限 1GB)"
fi

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
# 第三步:交给 workflow
# ============================================================================
step "产物已就绪,等 workflow 交给 Pages"
echo "    站点:$SITE_URL/"
echo "    目录:$BUILD_DIR"
( cd "$BUILD_DIR" && find . -type f | sort | sed 's|^\./|      |' )
echo
echo "    下一步(workflow 里):upload-pages-artifact → deploy-pages → publish-site.sh --verify"
echo "    判据没做完 —— 线上那份字节要等部署后才能逐字节比,那一步是 --verify。"
