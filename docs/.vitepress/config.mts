import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/gaia/',
  lang: 'zh-CN',
  title: 'Gaia',
  description: '盖亚输入法：为 Unihertz Titan 2 全键盘设计的硬件键盘拼音输入法——三层键盘、五键选字、上滑飞字、L4 虚拟按键',
  lastUpdated: true,
  cleanUrls: true,

  head: [
    ['meta', { name: 'theme-color', content: '#2f81f7' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Gaia 盖亚输入法 — 为 Titan 2 全键盘设计的硬件键盘拼音输入法' }],
    [
      'meta',
      {
        property: 'og:description',
        content: '三层键盘、五键选字、上滑飞字、L4 虚拟按键；Android 8.0+，为 Unihertz Titan 2 优化',
      },
    ],
  ],

  themeConfig: {
    nav: [
      { text: '功能', link: '/guide/features' },
      { text: '下载', link: '/guide/install' },
      { text: '常见问题', link: '/guide/faq' },
      { text: '关于', link: '/guide/about' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Gaia',
          items: [
            { text: '功能详解', link: '/guide/features' },
            { text: '下载与安装', link: '/guide/install' },
            { text: '常见问题', link: '/guide/faq' },
            { text: '关于与致谢', link: '/guide/about' },
          ],
        },
      ],
    },

    outline: { level: [2, 3], label: '本页目录' },

    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdatedText: '最后更新',
    darkModeSwitchLabel: '外观',
    sidebarMenuLabel: '目录',
    returnToTopLabel: '回到顶部',

    footer: {
      message: 'Gaia（盖亚输入法）为闭源发行；内置词库数据来自 jieba（MIT）与 pinyin-pro（MIT）。',
      copyright: 'Copyright © 2026 Gaia',
    },
  },
})
