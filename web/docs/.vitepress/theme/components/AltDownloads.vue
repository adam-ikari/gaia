<template>
  <div class="gi-alt">
    <!--
      ## 这一段在讲什么(2026-10-08 重排)

      原来的形状是「一段说明 + 三个应用名 + 两个描边按钮 + 两行校验值」,
      四个问题都在这段里,而且第 4 条只有截图才看得出来:

      1. **名字撞车**。主渠道和 β 在页面上都叫「测试版」,于是发布脚本的提示
         写成「建议改用下面的测试版」,而那句话正显示在「下载测试版」按钮下面
         —— 读者分不出该点哪个。⇒ 页面上只有主渠道保留「测试版」,β 与 dev
         改用在**系统里看得到的应用名**称呼(`app_name`,见 `../../download.ts`)。
      2. **三个应用名并列,可只有一个包能下**。gaia 上现在没有任何含
         `app-beta-*` 资产的 release,β 长期不上线;把它的名字印在页面上
         等于**声明一个不存在的渠道**。⇒ 名字从可用渠道生成,没上线的整个不出现。
      3. **正文在讲安装机制,而且层层递进**。原句「并存、互不覆盖、数据和设置
         各自分开 —— 所以你可以一边用着现在这个,一边试新的,出问题随时切回来」
         是一句因果链,尾巴那句还是**承诺**(切回来不丢正在打的词频?不丢)。
         ⇒ 只留一句能核验的事实:它们是各自独立的应用,可以一起装着。
      4. **这一段落在 `ink` 底色上**(band 轮换:stone/ink/white),而样式当初
         按浅色页面写,`color: var(--vp-c-text-2)` 在深底上是深灰字 ⇒ 读不清。
         ⇒ 组件内不再指定正文字色,交给 `.gaia-band` 的 `color: inherit`,
            深浅两种底色都对。
    -->
    <template v-if="!channels.length">
      <!--
        标题两种状态都给。空状态只甩一句正文时,这一节在页面上就是一块
        「什么都没有」的深色区域,像渲染坏了 —— 标题在,读者才知道这是
        「暂时没有别的构建」这件事,而不是一段没渲染出来的内容。
      -->
      <h3 class="gi-alt-title">暂时没有别的构建。</h3>
      <p class="gi-alt-lead">上面那个是现在唯一的包。</p>
    </template>
    <template v-else>
      <!--
        标题写在组件里、不写在 `index.md`:`::: band` 是会照常渲染的空色块,
        标题进了 markdown 就没法跟着渠道状态一起换。
      -->
      <h3 class="gi-alt-title">还有更新的构建。</h3>
      <p class="gi-alt-lead">
        这些构建和上面那个是各自独立的应用，可以一起装着，词库和设置互不影响。
      </p>

      <ul class="gi-alt-list">
        <li v-for="c in channels" :key="c.app" class="gi-alt-item">
          <p class="gi-alt-name">{{ c.app }}<span class="gi-alt-ver">{{ c.ver }}</span></p>
          <p class="gi-alt-desc">{{ c.desc }}</p>
          <!--
            **`download` 属性不能省**(2026-10-02 真机报障:点按钮是 404 页,
            直接访问同一 URL 却能下载)。VitePress 把站内 `.apk` 链接交给前端路由,
            `treatAsHtml` 的已知扩展名表里没有 apk → preventDefault → 404 页。
            文件名也要给:浏览器默认拿 URL 最后一段,而 URL 是固定的
            `gaia-dev.apk`,里面**没有身份**;存到手机上的名字得带着 commit。
          -->
          <p class="gi-alt-btns">
            <a class="gi-alt-btn" :href="c.url" :download="c.filename">{{ c.button }}</a>
          </p>
          <!-- 校验值紧跟在本渠道按钮下面:用户刚点完下载、最可能发现「下坏了」的位置。 -->
          <VerifyLine v-if="c.sha" :sha="c.sha" :size="c.size" label="校验" />
        </li>
      </ul>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * β 与 dev 两条渠道的列表。主渠道不在这里 —— 它是同一节里上面的 `Downloads.vue`。
 *
 * ## 为什么它是**次级标题**,而且和主渠道同屏
 *
 * 首屏只有一个视觉重心,并列的实心按钮会把主渠道这个唯一的主行动稀释掉,
 * 而「想试新的」是自己找的入口,不该让人以为「这才是该下的那个」。
 * ⇒ 描边按钮 + h3,排在主渠道下面。
 *
 * **同屏这件事本身是这次重排的目的**:原来主渠道在页面最后一节、这一节在第二节,
 * 两边互相指「上面的开发版」「下面的测试版」,而方向写反了没人发现。
 * 现在两条渠道在同一屏里,指代词只剩「上面 / 下面」这种**看得见**的相对位置。
 *
 * ## 为什么渠道是**算出来的数组**而不是写死两块
 *
 * 可用性只有构建期知道:`VITE_HAS_BETA` / `VITE_HAS_DEV` 由发布脚本在**真的取到
 * 那个包**之后才给 1(`../../download.ts`)。站点是纯静态,没有运行时可探测;
 * 而一个指向 404 的按钮比没有按钮更糟 —— 它让人以为「点不动是我网络的问题」。
 * 写成两块固定 markup,就得靠两遍 `v-if` 各自记得把名字、说明、校验值一起藏掉;
 * 藏不干净的表现正是上面第 2 条。
 *
 * ## URL 与文件名都从 `../../download` 取,不在这儿写死
 *
 * 直接写 `href="/media/gaia-dev.apk"` 渲染出来少了 `/gaia` 前缀 ⇒ **404**。
 * `download.ts` 里的 `withBase` 就是为这件事存在的;首屏那个按钮踩过一次。
 */
import { computed } from 'vue'
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
  HAS_BETA,
  HAS_DEV,
} from '../../download'

interface Channel {
  app: string
  ver: string
  desc: string
  button: string
  url: string
  filename: string
  sha: string
  size: string
}

/**
 * **这一节不说「修复在哪条线上」**(2026-10-08 定案)。
 *
 * 第一版给 dev 挂了一句「上面那个包的已知问题，修复在这一条线上」。
 * 它当时就不成立:页面上那个 dev 包是 `c271426`,而修复 `cd04b5a` 在它**之后**
 * —— CI 还没重新出过 dev 包。「这条线」有修复、「这个包」没有,
 * 而用户装的是**包**。
 *
 * 页面也不该自己去推「现在这个包含不含某个修复」:那要读 commit 关系,
 * 而站点是纯静态、构建期只拿到一个 hash。
 * ⇒ 「哪个包带上了什么」是**发布的人**在该发布的时候说的话,
 *   它的入口是 `STABLE_NOTE`(见 `tools/release/publish-site.sh`)。
 */
const channels = computed<Channel[]>(() => {
  const list: Channel[] = []
  if (HAS_BETA) {
    list.push({
      app: '盖亚输入法 β',
      ver: BETA_VERSION,
      desc: '发出来给人先试的构建。',
      button: '下载 β 版',
      url: BETA_DOWNLOAD_URL,
      filename: BETA_FILENAME,
      sha: BETA_SHA256,
      size: BETA_SIZE,
    })
  }
  if (HAS_DEV) {
    list.push({
      app: '盖亚输入法 dev',
      ver: DEV_VERSION,
      desc: '最新一次提交的构建，没在真机上验证过，不做任何保证。',
      button: '下载 dev 版',
      url: DEV_DOWNLOAD_URL,
      filename: DEV_FILENAME,
      sha: DEV_SHA256,
      size: DEV_SIZE,
    })
  }
  return list
})
</script>

<style scoped>
/*
 * 这一节和主渠道(`Downloads.vue`)**在同一屏里**,所以它是一条分界线下面的
 * 次级标题:用 h3,不用 h2 —— 一个 band 里两个 h2 会让页面看起来像两节。
 */
.gi-alt {
  margin-top: 36px;
  padding-top: 28px;
  border-top: 1px solid rgba(128, 128, 128, 0.3);
}

.gi-alt-title {
  margin: 0 0 12px;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
  color: inherit;
}

/* 正文字色一律不写:这一段可能落在任何底色上,继承 `.gaia-band` 的 color 才对。 */
.gi-alt-lead {
  color: inherit;
  opacity: 0.78;
}

.gi-alt-list {
  list-style: none;
  margin: 28px 0 0;
  padding: 0;
}

.gi-alt-item + .gi-alt-item {
  margin-top: 28px;
  padding-top: 28px;
  /* 中性灰:这条分隔线要在浅底和深底上都看得见,又不能抢正文。 */
  border-top: 1px solid rgba(128, 128, 128, 0.3);
}

.gi-alt-name {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: inherit;
  opacity: 1;
}

.gi-alt-ver {
  margin-left: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  font-weight: 400;
  opacity: 0.6;
}

.gi-alt-desc {
  margin: 6px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: inherit;
  opacity: 0.72;
}

.gi-alt-btns {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
  margin: 14px 0 0;
}

/*
 * **刻意比首屏主 CTA 弱**:描边,不是实心主色。
 * 但描边用品牌色,所以仍认得出一等公民,不是脚注。
 */
.gi-alt-btn {
  display: inline-flex;
  align-items: center;
  padding: 8px 18px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 980px;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  color: var(--vp-c-brand-1);
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.gi-alt-btn:hover {
  background: var(--vp-c-brand-1);
  color: #fff;
}
</style>
