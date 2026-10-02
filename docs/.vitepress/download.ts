/**
 * 下载链接与下载文件名 —— **两处按钮的唯一来源**(首屏 hero 与页尾)。
 *
 * ## URL 固定,文件名带版本
 *
 * 站点上的链接是 `/media/gaia.apk`,**永远指向最新版**,发版不用改 md。
 * 用户存下来的文件名是 `gaia-ime-v<版本>.apk` —— 文件名由 `<a download>` 给出,
 * 与 URL 无关。
 *
 * 为什么不把版本塞进 URL:那就要每发一版改一次 `index.md`,而漏改的后果是
 * 404 或者永远下不到新版 —— 正是这个站已经踩过的坑。
 *
 * ## 版本从构建期注入
 *
 * `VITE_APP_VERSION` 由 `deploy.yml` 从 release tag(`v0.4.3` → `0.4.3`)导出。
 * 本地构建没有这个变量(见 `.env.example` 的说明),此时退回 `gaia-ime.apk` ——
 * 能下,只是不带版本号。
 *
 * ## 为什么要留在本站
 *
 * `<a download>` 只对**同源**生效,跨源会被浏览器忽略。所以包必须由 Pages 自己发
 * (`deploy.yml` 从本仓库 release 取来放进产物),不能链 GitHub 的 release 下载地址。
 */

/** 站点上的路径。固定,不带版本。 */
export const DOWNLOAD_PATH = '/media/gaia.apk'

/** 本地构建等拿不到版本号时的兜底文件名。 */
const FALLBACK_FILENAME = 'gaia-ime.apk'

/** `v0.4.3` → `0.4.3`;容忍已经写成 `0.4.3` 或前面多个 v 的情况。 */
function normalizeVersion(tag: string): string {
  return tag.replace(/^v+/, '').trim()
}

const version = import.meta.env.VITE_APP_VERSION

/** 用户存到手机上的文件名,例如 `gaia-ime-v0.4.3.apk`。 */
export const DOWNLOAD_FILENAME = version
  ? `gaia-ime-v${normalizeVersion(version)}.apk`
  : FALLBACK_FILENAME

/** VitePress 的 `base` 要作用到这个路径上,所以两边都过 `withBase`,不能各写一遍。 */
import { withBase } from 'vitepress'

export const DOWNLOAD_URL = withBase(DOWNLOAD_PATH)
