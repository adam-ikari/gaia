/**
 * 两条下载渠道 —— **全部链接与文件名的唯一来源**。
 *
 * | 渠道 | 站点路径(固定) | 下载文件名 | 装到手机上叫什么 |
 * | --- | --- | --- | --- |
 * | Beta版 | `/media/gaia-beta.apk` | `gaia-ime-beta-<hash>.apk` | `盖亚输入法 β` |
 * | dev版 | `/media/gaia-dev.apk` | `gaia-ime-dev-<hash>.apk` | `盖亚输入法 dev` |
 *
 * ## 为什么没有正式版渠道(2026-10-23)
 *
 * 这个项目从来没有正式版。原来的主渠道只是一个挂着「已撤回」状态的旧包,
 * 留着它并不会让人更放心,反而把「站点在推荐一个已知有问题的构建」写在首页上。
 * ⇒ 站点只发 Beta版 与 dev;Beta版是推荐下载。
 *
 * ## 页面上怎么称呼
 *
 * 2026-10-23 起站点写 `Beta版` / `dev版`,不用希腊字母 β(用户要求)。
 * 应用内的名字仍是 `盖亚输入法 β` / `盖亚输入法 dev`
 * (`app/src/{beta,dev}/res/values/strings.xml` 的 `app_name`)。
 *
 * 版本号显示的是 APK 里的 `versionName`(`0.7.11-beta.a2f69d8`),
 * 也是装完在设置页看到的那个字符串。
 *
 * ## 为什么 URL 固定、身份进文件名
 *
 * 沿用这个站已经定过的原则:链接永远是 `/media/gaia-beta.apk`,**发版不用改 md**。
 * 原因不是偷懒 —— 把版本塞进 URL 意味着每发一版改一次 `index.md`,
 * 而漏改的后果是 404 或者**永远下不到新版**,这正是这个站踩过的坑。
 * 用户存下来的文件名由 `<a download>` 给,与 URL 无关。
 *
 * ## 身份从哪来
 *
 * 两条渠道的身份都是 **commit 短 hash**,由 `tools/release/publish-site.sh`
 * 在**构建期**从 release 资产名读出来,注入 `VITE_BETA_VERSION` / `VITE_DEV_VERSION`;
 * 完整版本号则由同一个脚本从下载到的 APK 的 `versionName` 读出,
 * 注入 `VITE_BETA_VERSION_NAME` / `VITE_DEV_VERSION_NAME`。
 * 不从 tag 推导:资产名是「远端确实存着什么」,是这件事的权威。
 * 若改成从 tag 推导,就有了 tag / 注入值 / APK 内 manifest 三个可能互相漂移的来源,
 * 不一致的表现是「文件名说 A、装完设置页写 B」。
 *
 * ## 可用性
 *
 * 测试版是主渠道:`publish-site.sh` 取不到它就不发布,gaia 的部署校验也按致命处理。
 * 开发版可选:`VITE_HAS_DEV` 由构建期给,取不到就留 0,对应按钮**整个不渲染**。
 *
 * 为什么不用运行时探测:站点是纯静态,没有「运行时」可探测。
 * 而且缺开发版**绝不能让部署失败** ——「还没有 dev 包」如果 `exit 1`,
 * 测试版也更新不了,站点停在上一次。一个可选功能能弄挂生产,这是最坏的形状。
 */

import { withBase } from 'vitepress'

/** 站点上的路径。固定,不带版本 —— 漏改 md 的可能性被结构性消灭。 */
export const BETA_DOWNLOAD_PATH = '/media/gaia-beta.apk'
export const DEV_DOWNLOAD_PATH = '/media/gaia-dev.apk'

/** 本地构建等拿不到版本号时的兜底文件名。 */
const FALLBACK_FILENAME = 'gaia-ime.apk'

/**
 * 判据是「非空且像个版本/hash」。
 *
 * 不能只判非空:`publish-site.sh` 取不到时给的是空串,空串会让文件名变成
 * `gaia-ime-beta-.apk` —— 那个尾巴看起来像**成功**了。
 * 宁可退回 `gaia-ime.apk`(不带版本号,一眼看得出没取到)。
 */
function usable(v: string | undefined): v is string {
  return !!v && v.trim().length > 0 && v.trim() !== 'undefined'
}

const betaVersion = import.meta.env.VITE_BETA_VERSION
const devVersion = import.meta.env.VITE_DEV_VERSION

export const HAS_DEV = import.meta.env.VITE_HAS_DEV === '1' && usable(devVersion)

/**
 * Beta版 与 dev 的**身份**(一个 commit 短 hash)。
 *
 * 文件名用它;页面上优先显示完整版本号,拿不到版本号时才退回它。
 */
export const BETA_VERSION = usable(betaVersion) ? betaVersion.trim() : ''
export const DEV_VERSION = HAS_DEV ? devVersion.trim() : ''

/**
 * 完整版本号(`0.7.11-beta.a2f69d8`),来自 APK 的 `versionName`。
 *
 * 按钮上显示的就是它 —— 有语义版本号才让人看得出「这一版比上一版新」,
 * 而 hash 只能回答「是哪个 commit」。装完在设置页看到的也是这个字符串。
 * 空串时按钮退回显示 hash。
 */
function versionName(v: string | undefined): string {
  const t = (v || '').trim()
  return /^\d+\.\d+/.test(t) ? t : ''
}

export const BETA_VERSION_NAME = versionName(import.meta.env.VITE_BETA_VERSION_NAME)
export const DEV_VERSION_NAME = HAS_DEV ? versionName(import.meta.env.VITE_DEV_VERSION_NAME) : ''

/**
 * beta/dev 的文件名 —— **hash 在前、渠道名在后**。
 *
 * 用户存下来的文件名会出现在自己的文件管理器里,那是他们唯一能拿回来问
 * 「这是哪个」的地方。所以渠道名必须在里面,而且要显眼。
 */
export const BETA_FILENAME = usable(betaVersion) ? `gaia-ime-beta-${betaVersion.trim()}.apk` : FALLBACK_FILENAME
export const DEV_FILENAME = HAS_DEV ? `gaia-ime-dev-${devVersion.trim()}.apk` : FALLBACK_FILENAME

/** VitePress 的 `base` 要作用到这个路径上,所以两边都过 `withBase`,不能各写一遍。 */
export const BETA_DOWNLOAD_URL = withBase(BETA_DOWNLOAD_PATH)
export const DEV_DOWNLOAD_URL = withBase(DEV_DOWNLOAD_PATH)

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
 * 来源是 `publish-site.sh` 对**已落地那个文件**算的 —— 即将被 Pages 发出去的那些字节,
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
