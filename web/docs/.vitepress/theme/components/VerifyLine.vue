<template>
  <!--
    一行下载校验值。

    ## 为什么放在这里、而不是页脚

    2026-10-06 实测:这个网络下下载**会静默截断**(有一次 `curl` 连文件都没
    落下来,而页面本身完全正常)。用户拿到残缺的 APK,安装器只给一句
    「软件包无效」,**无从知道是包坏了还是自己网络的问题**。

    而用户点完下载、最可能发现「下坏了」的位置就是按钮旁边 ——
    校验值放在页脚,等于要他先装一次、失败了、再回来找。

    ## 为什么这么写(而不是直接显示完整 sha256)

    完整 64 位十六进制在窄屏上会折行,而**折行的校验值最容易被抄错**。
    折成 4 段、段间留空格,便于逐段核对;并且**不隐藏** —— 用可复制文本,
    不用 canvas 画,也不用 `user-select: none`。
  -->
  <span class="gi-verify">
    <span class="gi-verify-label">{{ label }}</span>
    <span v-if="pretty" class="gi-verify-size">{{ pretty }}</span>
    <code v-if="short" class="gi-verify-sha">{{ short }}</code>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { prettySize, shortHash } from '../../download'

const props = defineProps<{
  /** 完整 sha256;空字符串 ⇒ 整个组件不渲染(见模板里的 v-if)。 */
  sha: string
  /** 字节数字符串。 */
  size: string
  label?: string
}>()

const pretty = computed(() => prettySize(props.size))
const short = computed(() => shortHash(props.sha))
</script>

<style scoped>
.gi-verify {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--vp-c-text-3);
  line-height: 1.6;
}

.gi-verify-label {
  color: var(--vp-c-text-2);
}

/* 等宽字体:校验值要能逐字符对齐核对 */
.gi-verify-sha {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  /* 不隐藏:用户要能选中复制去对 */
  -webkit-user-select: text;
  user-select: text;
}

.gi-verify-size {
  white-space: nowrap;
}
</style>
