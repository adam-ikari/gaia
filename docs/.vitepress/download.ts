/**
 * 三条下载渠道 —— **全部链接与文件名的唯一来源**。
 *
 * | 渠道 | 站点路径(固定) | 下载文件名 |
 * | --- | --- | --- |
 * | 正式版 | `/media/gaia.apk` | `gaia-ime-v0.7.11.apk` |
 * | 测试版 | `/media/gaia-beta.apk` | `gaia-ime-beta-<hash>.apk` |
 * | 开发版 | `/media/gaia-dev.apk` | `gaia-ime-dev-<hash>.apk` |
 *
 * ## 为什么 URL 固定、身份进文件名
 *
 * 沿用这个站已经定过的原则:链接永远是 `/media/gaia.apk`,**发版不用改 md**。
 * 原因不是偷懒 —— 把版本塞进 URL 意味着每发一版改一次 `index.md`,
 * 而漏改的后果是 404 或者**永远下不到新版**,这正是这个站踩过的坑。
 * 用户存下来的文件名由 `<a download>` 给,与 URL 无关。
 *
 * ## 三条渠道的身份分别从哪来(别处不要再造一个)
 *
 * - **正式版**:`VITE_APP_VERSION`,由 `deploy.yml` 从 release tag 剥 `v` 注入。
 * - **测试版 / 开发版**:`VITE_BETA_VERSION` / `VITE_DEV_VERSION`,
 *   由 `deploy.yml` **从 release 资产名**读出来 —— 不是从 tag、不是从别处。
 *
 *   资产名是「远端确实存着什么」,是这件事的权威。若改成从 tag 推导,
 *   就有了 tag / 注入值 / APK 内 manifest 三个可能互相漂移的来源,
 *   不一致的表现是「文件名说 A、装完设置页写 B」。
 *
 * ## 可用性由构建期 flag 决定,不是运行时探测
 *
 * `VITE_HAS_BETA` / `VITE_HAS_DEV` 由 `deploy.yml` 给:**取到了那个渠道才给 1**。
 * 取不到就发 warning 并把 flag 留 0,对应按钮**整个不渲染**。
 *
 * 为什么不用运行时探测:站点是纯静态,没有「运行时」可探测。
 * 而且缺一个可选渠道**绝不能让部署失败** ——
 * 「还没发过 beta」如果 `exit 1`,正式版也更新不了,站点停在上一次。
 * 一个可选功能能弄挂生产,这是最坏的形状。
 * (stable 是致命的:它本来就该有,没有就是发布流程断了。)
 */

/** 站点上的路径。固定,不带版本 —— 漏改 md 的可能性被结构性消灭。 */
export const DOWNLOAD_PATH = '/media/gaia.apk'
export const BETA_DOWNLOAD_PATH = '/media/gaia-beta.apk'
export const DEV_DOWNLOAD_PATH = '/media/gaia-dev.apk'

/** 本地构建等拿不到版本号时的兜底文件名。 */
const FALLBACK_FILENAME = 'gaia-ime.apk'

/** `v0.4.3` → `0.4.3`;容忍已经写成 `0.4.3` 或前面多个 v 的情况。 */
function normalizeVersion(tag: string): string {
  return tag.replace(/^v+/, '').trim()
}

/**
 * 判据是「非空且像个版本/hash」。
 *
 * 不能只判非空:`deploy.yml` 取不到时给的是空串,空串会让文件名变成
 * `gaia-ime-v.apk` —— 那个尾巴看起来像**成功**了。
 * 宁可退回 `gaia-ime.apk`(不带版本号,一眼看得出没取到)。
 */
function usable(v: string | undefined): v is string {
  return !!v && v.trim().length > 0 && v.trim() !== 'undefined'
}

const version = import.meta.env.VITE_APP_VERSION

export const DOWNLOAD_FILENAME = usable(version)
  ? `gaia-ime-v${normalizeVersion(version)}.apk`
  : FALLBACK_FILENAME

const betaVersion = import.meta.env.VITE_BETA_VERSION
const devVersion = import.meta.env.VITE_DEV_VERSION

export const HAS_BETA = import.meta.env.VITE_HAS_BETA === '1' && usable(betaVersion)
export const HAS_DEV = import.meta.env.VITE_HAS_DEV === '1' && usable(devVersion)

/**
 * beta/dev 的文件名 —— **hash 在前、渠道名在后**。
 *
 * 为什么不写成 `beta-<hash>.apk` 之外的样子:用户存下来的文件名会出现在
 * 自己的文件管理器里,那是他们唯一能拿回来问「这是哪个」的地方。
 * 所以渠道名必须在里面,而且要显眼。
 */
export const BETA_FILENAME = HAS_BETA ? `gaia-ime-beta-${betaVersion.trim()}.apk` : FALLBACK_FILENAME
export const DEV_FILENAME = HAS_DEV ? `gaia-ime-dev-${devVersion.trim()}.apk` : FALLBACK_FILENAME

/** VitePress 的 `base` 要作用到这个路径上,所以两边都过 `withBase`,不能各写一遍。 */
import { withBase } from 'vitepress'

export const DOWNLOAD_URL = withBase(DOWNLOAD_PATH)
export const BETA_DOWNLOAD_URL = withBase(BETA_DOWNLOAD_PATH)
export const DEV_DOWNLOAD_URL = withBase(DEV_DOWNLOAD_PATH)

/* ------------------------------------------------------------------ 正式版的状态 */

/**
 * `official` —— 正常发版。
 * `withdrawn` —— **有已知问题,已撤回**,不该当正式版推。
 *
 * ## 为什么状态是构建期注入的,而不是从 release 推出来的
 *
 * 推不出来:GitHub 上没有「这个版本是经流水线发的」这种标记。v0.7.10 是在
 * 应用仓 Actions 账单锁定时**手工**发的(见那个 tag 的 message),而它正被站点
 * 当正式版推。所以「这个包可不可信」**只有发布的人知道**,必须写下来。
 *
 * ## 为什么这必须有,而不是「页面文案问题」
 *
 * 站点发出去的是**用户直接下载安装的字节**。把一个已知有缺陷的构建放在
 * 主 CTA 上、且不作任何标注,后果是:用户装了一个坏的输入法,而站点
 * 每一个信号都在说这是正式版。**这比不提供下载更糟** ——
 * 不提供只是没得到,提供了却得到坏的、还被告知这是好的。
 */
export type StableStatus = 'official' | 'withdrawn'

const rawStatus = (import.meta.env.VITE_STABLE_STATUS || '').trim()
export const STABLE_STATUS: StableStatus = rawStatus === 'official' ? 'official' : 'withdrawn'

/**
 * 撤回时显示在主按钮下面的说明。空字符串(没注入)时不显示任何东西,
 * 但 `STABLE_STATUS` 仍然是 `withdrawn` —— **缺文案不等于没问题**。
 *
 * 这条不对称是有意的:注入漏了的后果是「按钮没有提示」,
 * 而把状态默认成 official 的后果是「明知道有问题却不说」。
 */
export const STABLE_NOTE = (import.meta.env.VITE_STABLE_NOTE || '').trim()
