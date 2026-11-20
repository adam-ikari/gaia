---
layout: apple-home
title: 盖亚输入法
tagline: 为 Titan 2 做的拼音。
note: 只为 Unihertz Titan 2
---

::: band stone
<div class="gaia-band-inner gaia-band-split">
<div class="gaia-band-copy">
<h2>每页五个，空格上屏。</h2>
<p>首选在正中间，空格就是它；另外四个分别在 Shift、Sym、Ctrl、Alt 上。也可以在这行键盘上往上滑选字。</p>
<p>Ctrl 那格要先把系统设置里的 Fn 键指派成 Ctrl。</p>
</div>
<div class="gaia-band-visual">
<CandidateSlots />
<Shot src="crop-01-idle.png" alt="空闲时的输入法行：左边是状态，中间是这一层能按的键，右边三个按钮。" />
</div>
</div>
:::

::: band ink
<div class="gaia-band-inner gaia-band-narrow">
<h2>翻页跟手。</h2>
<p>翻页时整页跟着手指走。滑到一半松手，它会退回原来那页，退回的过程从当前位置接着算。</p>
<Shot src="crop-04-page-swipe.png" alt="左右滑动后的候选行：五格候选换成新的一页。" />
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-split">
<div class="gaia-band-copy">
<h2>往上滑，也算选词。</h2>
<p>屏幕下面这行横向切五份，手指落在哪份就选哪份。往上划多少决定的是<strong>划到哪儿才算数</strong>，跟落在哪一份没有关系。</p>
<p>划够了就上屏，不用等抬手。直接点也行。</p>
</div>
<div class="gaia-band-visual">
<ZoneStrip />
</div>
</div>
:::

::: band ink
<div class="gaia-band-inner gaia-band-narrow">
<h2>快慢有三档。</h2>
<p>上滑选字和左右翻页各有三档：灵敏、标准、保守。方向键翻页可以反向，震动可以关。</p>
<p>刚按完物理键，紧接着划一下不算选字。免得按完键顺手带出别的字。</p>
</div>
:::

::: band stone
<div class="gaia-band-inner gaia-band-narrow">
<h2>键盘分三层。</h2>
<p>字母、符号数字、编程。按 Alt 或 Sym 换层：按一下临时切过去，双击锁住，锁住后再按一下解开。</p>
<p>Titan 2 没有独立数字行，符号得换层。中英切换按 Shift 加空格，锁着哪层都管用。</p>
<Shot src="crop-07-alt-layer.png" alt="状态行：左边依次是 拼、全、alt，alt 下面有一道下划线；中间一排是这一层能按出来的符号键；右边是设置和收起。" />
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-narrow gaia-band-wide">
<h2>Alt 层照键帽印的。</h2>
<p>按住 Alt 再按字母键，打出来的是键帽侧面那列符号。照 Titan 2 出厂丝印排，键帽上印什么，这里就打什么。</p>
<KeyCapMap layer="L2" note="大字 = 按住 Alt 时这把键打出来的东西；小字 = 键帽上印的字母。这是键位表，不是屏幕截图。" />
</div>
:::

::: band ink
<div class="gaia-band-inner gaia-band-narrow gaia-band-wide">
<h2>Sym 层我们自己排。</h2>
<p>这层放编程符号，键帽上没有可照的，我们自己排。成对的括号挨在一起：<code>&lt;</code> <code>&gt;</code>、<code>[</code> <code>]</code>、<code>{</code> <code>}</code> 各占相邻两格。</p>
<p>换成全角后，十几格换成中文写法：<code>￥</code>、<code>《》</code>、<code>【】</code>、<code>、</code>。方向键、Esc、Tab、翻页的位置不变。</p>
<KeyCapMap layer="L3" note="大字 = 按住 Sym 时这把键打出来的东西；小字 = 键帽上印的字母。这一层是输入法自己排的。" />
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-narrow">
<h2>框不一样，键不一样。</h2>
<p>网址框换成网址开头的键，邮箱框换成 .com、@qq.com 这些常用的结尾；数字框里，数字直接锁上。</p>
<p>电话框给数字加 <code>+</code> <code>-</code>；日期时间框给 <code>-</code> <code>:</code> 和一串零。哪类框给什么，出厂就带着，不用配。</p>
<Shot src="crop-05-url-field.png" alt="网址框聚焦时的状态行：左边是 拼、全，中间一排依次是 ; … -- http:// https:// www.，右边是键盘图标、设置和收起。" />
<Shot src="crop-06-number-field.png" alt="数字框聚焦时的状态行：左边那一项写着 0-9，下面有一道下划线，表示这一层锁住了。" />
</div>
:::

::: band stone
<div class="gaia-band-inner gaia-band-narrow">
<h2>排序交给 Rime。</h2>
<p>引擎在应用里，叫 Rime，开源的。词频也归它管：你打得多的词会慢慢往前挪。</p>
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-narrow">
<h2>词库在包里。</h2>
<p>随包带着三万八千多个词、六万七千多条读法。一个词有几种读法就收几条。</p>
<p>懒得打全拼就敲首字母：<code>nh</code> 就是「你好」。拼音串会自动按音节分开，看得到自己打成了什么。</p>
<Shot src="crop-02-composing.png" alt="组词中的候选行：整行横排几格，正中间那格是首选，字比别处亮。" />
<Shot src="crop-03-committed.png" alt="空格上屏后的候选行：中间那格的首选已经上了屏。" />
</div>
:::

::: band ink
<div class="gaia-band-inner gaia-band-narrow">
<h2>按住空格说话。</h2>
<p>没在打字时，长按空格说一句话，松手字就上屏。说的过程中，键盘上方那排小格跟着声音起伏。</p>
<p>识别在手机里完成，不用注册，也不用填密钥。只认中文。</p>
<p>这个开关默认关着，要去设置里打开，并同意用麦克风。密码框和纯数字框不会启动。</p>
<p>语音模型随包安装，下载包约 170MB，比之前大不少。</p>
</div>
:::

::: band stone
<div class="gaia-band-inner gaia-band-split">
<div class="gaia-band-copy">
<h2>设置里能改。</h2>
<p>七套配色。字母键长按直接出本层符号，中文态的符号层可以切全角。</p>
<p>打得久了，常用词会排上来。想恢复出厂，把学习记录清掉。</p>
</div>
<div class="gaia-band-visual">
<Shot src="08-settings.png" alt="应用内的设置页：常规、按键与层、词库几组开关。" />
<Shot src="09-licenses.png" alt="应用里列出的第三方组件与许可。" />
</div>
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-narrow">
<h2>装它之前。</h2>
<p>Android 8.0 及以上，只给 Unihertz Titan 2 做过。</p>
<p>包比一般输入法大，因为语音模型跟着一起装。</p>
<p>下载旁边写着大小和校验值。这里的网络会静默截断下载，安装器只说「软件包无效」。先对这两个数，就知道是没下全还是包坏了。</p>
</div>
:::

::: band ink
<div class="gaia-band-inner gaia-band-narrow">
<h2>怎么装。</h2>
<p>一、装应用。系统会问要不要允许这个来源，允许。</p>
<p>二、打开系统设置里的语言和输入法，找到盖亚输入法，启用它并选为当前输入法。</p>
<p>三、把 Fn 键指派给 Ctrl。设置 → 快捷键与手势（部分系统版本叫键盘设置）→ Fn 键 → 选 Ctrl。不这么做的话，五个词里有一个键位是空的。Alt、Sym、Shift 不用设，直接可用。</p>
</div>
:::

::: band white
<div class="gaia-band-inner gaia-band-narrow">
<h2>只为 Titan 2 做的。</h2>
<p>别的全键盘机型没测过，键位可能对不上。系统的输入法随时能切回来。</p>
</div>
:::
