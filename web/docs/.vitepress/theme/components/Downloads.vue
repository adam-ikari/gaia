<template>
  <div class="gi-dl">
    <!--
      ## 两个按钮(2026-10-23)

      一条渠道一个按钮,按钮上写清是哪条渠道、哪个 commit,下面一行校验值。
      β 是推荐下载(实心),dev 是可选(描边)。站点不再有正式版渠道 ——
      这个项目从来没有正式版,原来的主渠道只是一份挂着「已撤回」的旧包。
    -->
    <p class="gi-dl-row">
      <span v-for="c in channels" :key="c.label" class="gi-dl-cell">
        <!--
          **`download` 属性不能省**(2026-10-02 真机报障:点按钮是 404 页,
          直接访问同一 URL 却能下载)。VitePress 把站内 `.apk` 链接交给前端路由,
          而 `treatAsHtml` 的已知扩展名表里没有 apk → preventDefault → 404 页。
          文件名也要给:URL 是固定的 `/media/gaia-beta.apk`,里面没有身份,
          而「哪个版本、哪个 commit」是用户唯一能拿回来问的东西。
        -->
        <a
          class="gi-dl-btn"
          :class="{ 'gi-dl-btn-alt': !c.primary }"
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
  </div>
</template>

<script setup lang="ts">
/**
 * 两条渠道的按钮。URL、文件名、校验值全部来自 `../../download`,这里不写死;
 * 可用性由构建期 flag 决定 —— 站点是纯静态,没有运行时可探测,
 * 而一个指向 404 的按钮比没有按钮更糟(它让人以为「点不动是我网络的问题」)。
 *
 * ## β 是主渠道
 *
 * β / dev 用 β / dev 这两个短名 —— 它们在手机上就是
 * `盖亚输入法 β` / `盖亚输入法 dev`(`app/src/{beta,dev}/res/values/strings.xml`),
 * 页面称呼和装完看到的称呼同一个词。
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
  HAS_DEV,
} from '../../download'

interface Channel {
  label: string
  url: string
  filename: string
  sha: string
  size: string
  primary?: boolean
}

/** 没有 hash 时只写渠道名 —— 空着不误导,有值才可信。 */
const channels: Channel[] = [
  {
    label: BETA_VERSION ? `β ${BETA_VERSION}` : '测试版',
    url: BETA_DOWNLOAD_URL,
    filename: BETA_FILENAME,
    sha: BETA_SHA256,
    size: BETA_SIZE,
    primary: true,
  },
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
 * 非默认渠道:描边。β 是唯一的默认推荐,两个并列的实心按钮会把这件事稀释掉。
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
</style>
