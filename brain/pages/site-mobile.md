---
id: site-mobile
title: "移动端:视口被撑开是首要故障,3D 投影的尺寸必须实测"
category: decision
status: active
tags: [mobile, responsive, css, 3d, viewport]
created: "2026-10-01T11:16:33"
updated: "2026-10-01T11:16:58"
---

<!-- compiled_truth -->
# 移动端版式:先量视口,再看图

## 一、首要故障不是"某个组件歪了",是**整页被缩小**

移动浏览器把 `<meta viewport width=device-width>` 里的宽度当**最小**宽度:
页面只要有一个元素横向溢出,整页就按更宽的布局视口排版,手机上看到的是全站缩小、
右侧一截被切掉。实测首页 375px 手机上 `window.innerWidth` 是 **559**,
即整站比该有的窄三分之一。

所以**先量 `innerWidth` 等不等于设备宽度**,再谈别的。量法:`puppeteer-core` +
`/usr/bin/chromium-browser`,`isMobile: true`。判据:

```js
window.innerWidth === 设备宽度   // 相等 = 视口没被撑开
```

不要只看 `document.documentElement.scrollWidth` —— 那个值在裁切生效后仍会偏大,
`canScrollX` 才是"用户能不能横向滚"的真判据。

## 二、这次的三个根因(按危害排序)

1. **`KeyboardStack` 的 3D 投影溢出**。`.kplane` 300px 宽,经 `rotateX(56deg) +
   rotateZ(-24deg)` 加透视后,**投影外接框实测 340–351px**,而默认 `overflow: visible`
   让它溢出到页面外 → 视口被撑到 559px。
   修法:`.kstack-stage` 上 `overflow: hidden; overflow: clip`。
   **必须用 `clip` 而不是只用 `hidden`** —— `clip` 不建立滚动容器,
   `position: sticky` 照常工作(首页 ScrollFrames 吸顶、导航都依赖);`hidden` 会破坏。
2. **`.gaia-home` 的左右负 margin**。`margin: -32px -24px 0` 里的 `-24px` 是白加的:
   `.VPContent` 左右 padding 本来就是 0、`.gaia-home` 已经满宽,于是多出 48px 宽、
   右侧溢出 24px。**通栏不靠这个负 margin**,满宽容器里分区底色自然到边。
3. **`gaia-band-split` 从不叠列**。`.gaia-band-inner.gaia-band-split` 是左右两栏,
   `responsive.css` 只在 `min-width:1024px` 给过 flex 基准,小于 1024 一直并排 ——
   375px 上两栏并排,演示件直接压在正文上。修法:`max-width:1023px` 叠成单列。

`html { overflow-x: clip }` 作为**兜底**留着:个别元素再溢出也只裁它自己,
不会把整站拖下水。

## 三、3D 里量出来的尺寸 ≠ CSS 里写的尺寸(最容易反复踩)

3D 变换后的**屏幕投影**与元素自身的布局尺寸是两回事。以下三个数都必须实测:

- **垂直位置**:板子摆在 `top: 50%`,经 `rotateX` + z 位移后整块**下坠约 116px**
  ⇒ 上面空 116px、下面被裁 117px。修:`transform: translate(-27px, -116px) rotateX(...) rotateZ(...)`。
  transform 从**右往左**应用,所以 translate 要写在链的**开头**才是屏幕方向的平移。
- **水平偏移**:同一个投影顺带把整块**右带约 27px**(375/320 两档实测一致,与板宽无关)。
- **命中区域**:层名按钮本地写 `min-height: 44px`,屏幕上只渲染出 **33–38px**
  (透视压缩比约 0.72)⇒ 本地要留到 `44 / 0.72 ≈ 61`,取 **64px**。
  改完必须量 `getBoundingClientRect().height`,**看 CSS 里的数会以为已经修好了**。

## 四、窄屏换交互形态,不要只缩小

320px 上层名(`.kplane-name`,绝对定位在板子左侧)被左边缘裁掉 33px,「字母」只剩半个。
与其把板子缩到 180px(每键只剩 30px,按不准),不如**把层名挪到板子下方横排**
(`.kstack-tabs`,默认 `display:none`,`max-width:400px` 时启用,每键 44px 满行)。
**窄屏上绝对定位的标签基本都要换形态**,不是调数值能救的。

## 五、命中区域的默认值(VitePress 自带的都不够)

手机上 44px 是底线,实测默认值:

| 元素 | 原尺寸 | 处理 |
|---|---|---|
| `.VPSidebarItem .item a`(抽屉主导航) | 32px | `min-height:44px` —— 要点的是里面的 `<a>`,**只给外层 `.item` 加高不起作用** |
| `button.menu`("目录",进侧栏的唯一入口) | 110×24 | `min-height:44px` |
| `.VPLocalNavOutlineDropdown > button` | 64×24 | 它是**没有 class 的裸 button**,只能按父级选 |
| `.gi-dl-btn`(下载按钮,整站唯一转化动作) | 42px | `min-height:44px` + `inline-flex` |
| `.VPDoc .header-anchor`(¶) | 18×44 | 视觉宽度不能改(会把标题挤换行)⇒ 用 `::after` + `position:absolute` 把**命中区域**撑到 44×56 |

正文里的行内链接按 44px 判会误报,量的时候单独归类。

## 六、体检脚本的形状(可复用)

`puppeteer-core` + 本机 `/usr/bin/chromium-browser`(版本 135),`--no-sandbox`。
遍历 7 档视口 × 5 个页面 = 35 组,每组滚一遍触发懒加载再量:

```
320 小手机 / 375 iPhoneSE / 390 iPhone14 / 412 Pixel7
844×390 横屏 / 576×576 Titan 2 / 820 平板
```

四项判据:`innerWidth` 是否等于设备宽、真实越界元素(排除 VitePress **关闭态**侧栏——
它在 `left: -279`,是抽屉收起来的状态,不是溢出)、非行内点击目标是否 ≥44px、
字号是否 ≥12px。

**误报要认出来**:VitePress 的 `div.item / div.indicator / h2.text` 报溢出,
其实是关闭态抽屉;`header-anchor` 报 24px 宽是因为我们刻意保持视觉窄、
用伪元素扩命中区。判据要写"排除哪些",否则会被自己的噪声淹没。

## 七、这轮的边界

只验了桌面(Chromium 无头 + 真机像素渲染),**没在真手机上看过**;
Titan 2 是按 576×576 的视口模拟的,不是那台机器。
`console` 里固定有一个 `404 /favicon.ico`(站点本来就没有图标)和一个 GitHub API 的
`403`(未认证限流,`Downloads.vue` 会退化成"无法连接"文案)—— 两个都**不是**版式问题。


## Timeline

- time: 2026-10-01T11:16:33
  kind: decision
  summary: "Created this page: 移动端:视口被撑开是首要故障,3D 投影的尺寸必须实测"
  source: "2026-10-01 移动端版式修复:35 组视口实测"
  affects: [site-mobile]

- time: 2026-10-01T11:16:58
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-mobile]
