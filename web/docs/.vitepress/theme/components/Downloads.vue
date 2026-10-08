<template>
  <div class="gi-dl">
    <!--
      ## 只有一排按钮(2026-10-08 定稿)

      这一节从前到后试过三种形状:三段说明文字 + 一排应用名标签 + 两节互相指
      「上面/下面」。都不如现在这个:**一条渠道一个按钮,按钮上写清是哪条渠道
      哪个版本,下面一行校验值**。剩下的话只留一句 —— 主渠道有已知问题时那句。

      几条被删掉的话,以及为什么本来就不该写:
        · 「默认下载:最新一次发出去的包」—— 标题就叫「下载」,这是重复。
        · 「这些构建和上面那个是各自独立的应用,可以一起装着,词库和设置互不影响」
          —— 装过的人知道,没装过的人不关心;它是在为设计解释,不是在给信息。
        · 「本站没有正式版:一直以来发出去的都是测试版」—— 按钮上「测试版」三个
          字已经说了这件事,再补一句是否认一段不存在的误会。
        · 每条渠道一句说明 —— 同上。渠道之间真正的区别(版本号 vs commit hash)
          写在按钮上了。
    -->
    <p class="gi-dl-row">
      <span v-for="c in channels" :key="c.label" class="gi-dl-cell">
        <!--
          **`download` 属性不能省**(2026-10-02 真机报障:点按钮是 404 页,
          直接访问同一 URL 却能下载)。VitePress 把站内 `.apk` 链接交给前端路由,
          而 `treatAsHtml` 的已知扩展名表里没有 apk → preventDefault → 404 页。
          文件名也要给:URL 是固定的 `/media/gaia.apk`,里面没有身份,
          而「哪个版本、哪个 commit」是用户唯一能拿回来问的东西。
        -->
        <a
          class="gi-dl-btn"
          :class="{ 'gi-dl-btn-alt': !c.primary, 'gi-dl-btn-withdrawn': c.withdrawn }"
          :href="c.url"
          :download="c.filename"
        >{{ c.label }}</a>
        <!--
          校验值紧跟在各自按钮下面,不放页脚:2026-10-06 实测这个网络下下载
          **会静默截断**,而安装器只给一句「软件包无效」。有大小和 sha256,
          用户才能自己分辨「没下全」和「包有问题」。
        -->
        <VerifyLine v-if="c.sha" :sha="c.sha" :size="c.size" label="校验" />
      </span>
    </p>

    <!--
      `withdrawn` 时这句**必须显示**。不是为了合规 —— 用户下载的是会装到手机上的
      字节,把已知有缺陷的构建放在默认位置上却不说明,比不给更糟。
      「修复在哪个包里」这里也不写:静态站在构建期只拿到一个 hash,推不出包内容,
      那句话归发布的人,写进 `STABLE_NOTE`(见 `tools/release/publish-site.sh`)。
    -->
    <p v-if="withdrawn && STABLE_NOTE" class="gi-dl-note">{{ STABLE_NOTE }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 三条渠道的按钮。URL、文件名、校验值全部来自 `../../download`,这里不写死;
 * 可用性由构建期 flag 决定 —— 站点是纯静态,没有运行时可探测,
 * 而一个指向 404 的按钮比没有按钮更糟(它让人以为「点不动是我网络的问题」)。
 *
 * ## 只有主渠道叫「测试版」
 *
 * 原来主渠道和 β 在页面上都叫测试版,于是提示语「建议改用下面的测试版」
 * 显示在「下载测试版」按钮下面,读者分不清该点哪个。现在 β/dev 用 β / dev
 * 这两个短名 —— 它们在手机上就是 `盖亚输入法 β` / `盖亚输入法 dev`
 * (`app/src/{beta,dev}/res/values/strings.xml`),页面称呼和装完看到的称呼同一个词。
 *
 * ## 深浅底色
 *
 * 这一节落在 band 轮换出的底色上(现在是 `ink` 深底),所以正文与降级按钮
 * **不用 `--vp-c-text-*` / `--vp-c-bg-soft`** —— 那些跟的是站点主题,
 * 深底上会变成深灰字。统一 `color: inherit` + `opacity`。
 */
import VerifyLine from './VerifyLine.vue'
import {
  BETA_DOWNLOAD_URL,
  BETA_FILENAME,
  BETA_SHA256,
  BETA_SIZE,
  BETA_VERSION,
  DEV_DOWNLOAD_URL,
  DEV_FILENAME,
  DEV_SHA256,
  DEV_SIZE,
  DEV_VERSION,
  DOWNLOAD_URL,
  DOWNLOAD_FILENAME,
  HAS_BETA,
  HAS_DEV,
  STABLE_NOTE,
  STABLE_SHA256,
  STABLE_SIZE,
  STABLE_STATUS,
} from '../../download'

interface Channel {
  label: string
  url: string
  filename: string
  sha: string
  size: string
  primary?: boolean
  withdrawn?: boolean
}

/** 版本号只用于显示:取不到就空着 —— 空着不误导,有值才可信。 */
const ver = (import.meta.env.VITE_APP_VERSION || '').trim().replace(/^v+/, '')

const withdrawn = STABLE_STATUS === 'withdrawn'

const channels: Channel[] = [
  {
    label: ver ? `测试版 ${ver}` : '测试版',
    url: DOWNLOAD_URL,
    filename: DOWNLOAD_FILENAME,
    sha: STABLE_SHA256,
    size: STABLE_SIZE,
    primary: true,
    withdrawn,
  },
  ...(HAS_BETA
    ? [{ label: `β ${BETA_VERSION}`, url: BETA_DOWNLOAD_URL, filename: BETA_FILENAME, sha: BETA_SHA256, size: BETA_SIZE }]
    : []),
  ...(HAS_DEV
    ? [{ label: `dev ${DEV_VERSION}`, url: DEV_DOWNLOAD_URL, filename: DEV_FILENAME, sha: DEV_SHA256, size: DEV_SIZE }]
    : []),
]
</script>

<style scoped>
.gi-dl {
  margin: 0 auto;
  max-width: 640px;
}

.gi-dl-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: flex-start;
  gap: 14px 12px;
  margin: 0;
}

.gi-dl-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
}

/*
 * 校验值那一行在窄屏上会排不下(320px 实测):「校验」两个字被拆成两行,
 * 而校验值本身折行最容易被抄错。⇒ 标签不许断行,换行让**整段校验值**换一行。
 */
.gi-dl-cell :deep(.gi-verify) {
  flex-wrap: wrap;
  justify-content: center;
  row-gap: 2px;
}

.gi-dl-cell :deep(.gi-verify-label) {
  white-space: nowrap;
}

.gi-dl-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 168px;
  min-height: 52px;
  padding: 12px 20px;
  border-radius: 14px;
  background: var(--vp-c-brand-1);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
  text-decoration: none;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  transition: background-color 0.2s ease, transform 0.2s ease;
}

.gi-dl-btn:hover {
  background: #0077ed;
}

.gi-dl-btn:active {
  background: #0069d6;
  transform: scale(0.99);
}

/*
 * 非默认渠道:描边。主渠道是唯一的默认推荐,三个并列的实心按钮会把这件事稀释掉。
 * 描边仍用品牌色,所以认得出一等公民,不是脚注。
 */
.gi-dl-btn-alt {
  background: transparent;
  border: 1px solid var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
  font-size: 15px;
}

.gi-dl-btn-alt:hover {
  background: var(--vp-c-brand-1);
  color: #fff;
}

/*
 * 有已知问题时主渠道**降级**:不再是实心主色。
 * 它仍然是最大的可点区域(那是转化路径,不该消失),但视觉权重降下来 ——
 * 一个「已知有问题」的下载不该长得和「推荐下载」一模一样。
 */
.gi-dl-btn-withdrawn {
  background: rgba(128, 128, 128, 0.16);
  color: inherit;
  border: 1px solid rgba(128, 128, 128, 0.45);
}

.gi-dl-btn-withdrawn:hover {
  background: rgba(128, 128, 128, 0.26);
}

.gi-dl-btn-withdrawn:active {
  background: rgba(128, 128, 128, 0.16);
}

.gi-dl-note {
  max-width: 34em;
  margin: 18px auto 0;
  color: inherit;
  opacity: 0.72;
  font-size: 13px;
  line-height: 1.6;
  text-align: left;
}
</style>
