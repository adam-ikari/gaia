/**
 * 三条下载渠道 —— **全部链接与文件名的唯一来源**。
 *
 * | 渠道 | 站点路径(固定) | 下载文件名 |
 * | --- | --- | --- |
 * | 测试版 | `/media/gaia.apk` | `gaia-ime-v0.7.11.apk` |
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
 * - **测试版**:`VITE_APP_VERSION`,由 `deploy.yml` 从 release tag 剥 `v` 注入。
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

/* ------------------------------------------------------------------ 主下载渠道的状态 */

/**
 * 这个项目**一直以来都是测试版** —— 没有任何一次发版是「正式版」。
 *
 * 所以主下载渠道的标签是**测试版**,不是「正式版」。
 *
 * ## 为什么不叫「正式版 · 已撤回」
 *
 * 我第一版写的是那个,用户指出问题:「一直以来都是测试版」。
 *
 * 「已撤回」这个词**预设了它曾经是正式版** —— 那是我在页面上凭空给它造的一段
 * 合法性。v0.7.10 是手工发的(应用仓 Actions 账单锁定)、从没跑过 E2E、
 * 就有已知缺陷,它从头到尾只是个测试版。「正式版 + 已撤回」比直接说「测试版」
 * 更糟:它在一件事上撒谎,而且用一个更醒目的标记盖住了另一件更该说的事。
 *
 * ⇒ 渠道名就是**测试版**。状态只有一种含义:**这个测试版有个已知问题**。
 */
export type StableStatus = 'official' | 'withdrawn'

const rawStatus = (import.meta.env.VITE_STABLE_STATUS || '').trim()
export const STABLE_STATUS: StableStatus = rawStatus === 'official' ? 'official' : 'withdrawn'

/**
 * 有已知问题时显示在按钮下面的说明。
 *
 * 空字符串(没注入)时只是不显示文字,而状态**仍然是 `withdrawn`** ——
 * **缺文案不等于没问题**。这个不对称是刻意的:
 * 注入漏了的后果是「按钮没有提示」;把状态默认成 official 的后果是
 * 「明知道有问题却不说」。后者严重得多。
 */
export const STABLE_NOTE = (import.meta.env.VITE_STABLE_NOTE || '').trim()

/* ---------------------------------------------------------------- 下载校验值 */

/**
 * 每条渠道的 sha256 与字节数,页面上要显示。
 *
 * ## 为什么必须有
 *
 * 2026-10-06 实测:这个网络下下载**会静默截断** —— 有一次 `curl` 连文件都
 * 没落下来,而页面本身完全正常。用户拿到残缺的 APK,安装器只给一句
 * 「软件包无效」,**无从知道是包坏了还是自己网络的问题**。
 *
 * 有了这两个数,用户能自己分辨「没下全」与「包有问题」——
 * 而分辨不出来的时候,唯一合理的动作是重下,或者放弃。
 *
 * 这与 `hwkbd_ime` 的 `sync-apk-to-site.sh` 里那个 bug 是同一件事的两端:
 * 那边是**我们**不该把截断的包发出去(判据只判存在、不判完整),
 * 这边是**用户**需要能验证自己拿到的包。两边都需要 sha256。
 *
 * 来源是 deploy.yml 对**已落地那个文件**算的 —— 即将被 Pages 发出去的那些字节,
 * 不是构建产物、更不是本地构建机上的那份。
 */
function sha(v: string | undefined): string {
  const t = (v || '').trim()
  // 64 位十六进制;不是就当没有。给一个错的长度和给没有一样坏。
  return /^[0-9a-f]{64}$/.test(t) ? t : ''
}

function bytes(v: string | undefined): string {
  const t = (v || '').trim()
  return /^\d+$/.test(t) ? t : ''
}

export const STABLE_SHA256 = sha(import.meta.env.VITE_STABLE_SHA256)
export const STABLE_SIZE = bytes(import.meta.env.VITE_STABLE_SIZE)
export const BETA_SHA256 = sha(import.meta.env.VITE_BETA_SHA256)
export const BETA_SIZE = bytes(import.meta.env.VITE_BETA_SIZE)
export const DEV_SHA256 = sha(import.meta.env.VITE_DEV_SHA256)
export const DEV_SIZE = bytes(import.meta.env.VITE_DEV_SIZE)

/** 字节数给人看:10681511 → "10.2 MB"。下载完对不上就是没下全。 */
export function prettySize(n: string): string {
  const b = Number(n)
  return b > 0 ? `${(b / 1048576).toFixed(1)} MB` : ''
}

/** sha256 折成 4 段,便于肉眼逐段核对。 */
export function shortHash(h: string): string {
  if (!h) return ''
  return (h.match(/.{1,8}/g) || []).slice(0, 4).join(' ')
}
