<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * 下载。**链接是写死的，版本信息才是走 API 取的** —— 这个顺序是刻意的。
 *
 * 为什么不用 API 拼链接：GitHub 未认证 REST 配额是 **60 次/小时、按出口 IP 计**。
 * 同一出口 IP 后面的人互相抢这 60 次，访客偶发 403 是常态（不是配置错）。
 * 而原来的写法把**链接本身**绑在这个配额上：一旦 403，页面连按钮都不渲染，
 * 只剩一句「无法连接 GitHub」—— 整站唯一的转化动作在限流时整块消失。
 *
 * 关键事实：`github.com/<repo>/releases/latest/download/<文件名>` 这条路径
 * 由 GitHub 站点直接服务（302 到 release-assets），**不走 REST 配额**。
 * 文件名固定，latest 是 GitHub 自己解析的，所以它既不消耗配额、
 * 又永远指向最新包。把按钮指向它，下载就与 API 无关了。
 *
 * API 现在只用来锦上添花：版本号、大小、下载次数。取不到就都不显示，
 * 但按钮照常能按 —— 装饰信息缺失不该让功能失效。
 *
 * 包挂在**公开的站点仓库**上（应用仓库是私有的，那边的链接要登录态）。
 * 站点只提供正式版：调试包不进这里。
 */
const REPO = 'adam-ikari/gaia'
const ASSET = 'app-release.apk'

/** 静态兜底链接：不依赖任何 API，永久有效（只要该仓库有 latest release）。 */
const DOWNLOAD_URL = `https://github.com/${REPO}/releases/latest/download/${ASSET}`

/** API 成功时才有的补充信息 */
const tag = ref('')
const size = ref('')
const count = ref<number | null>(null)

function mb(bytes: number) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

onMounted(async () => {
  /*
   * 全部包在独立的 try 里，任何一步失败都只是少了点装饰信息。
   * 刻意**不再**往界面上抛「无法连接 / 请稍后重试」—— 那句话没有可执行的下一步，
   * 之前它还顶掉了下载按钮，现在更不该由它出现。
   *
   * 限流是常态而非异常（60 次/小时共享），所以不做重试：重试只会更快耗光配额，
   * 而且用户看到的内容不会因此变好。
   */
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`)
    if (!res.ok) return
    const data = await res.json()
    tag.value = data.tag_name ?? ''
    // 只认 release 包；防御性过滤，万一以后传错了也不会露出来
    const asset = (data.assets ?? []).find(
      (a: { name: string }) => a.name === ASSET
    )
    if (asset) {
      size.value = mb(asset.size)
      count.value = typeof asset.download_count === 'number' ? asset.download_count : null
    }
  } catch {
    /* 拿不到就算了，按钮不依赖这些 */
  }
})
</script>

<template>
  <div class="gi-dl">
    <a class="gi-dl-btn" :href="DOWNLOAD_URL" download>下载安装包</a>

    <!-- 补充信息：API 拿到了才显示。取不到时按钮照常能用，这里什么都不出现。 -->
    <p v-if="tag" class="gi-dl-meta">
      当前版本 <strong>{{ tag }}</strong><template v-if="size"> · {{ size }}</template
      ><template v-if="count !== null"> · 已下载 {{ count }} 次</template>
    </p>
  </div>
</template>

<style scoped>
.gi-dl {
  margin: 0 auto;
  max-width: 560px;
}

.gi-dl-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  /* 整站唯一真正的转化动作，撑满一行：文件名长、按错成本高，没有理由做小 */
  width: 100%;
  min-height: 52px;
  padding: 12px 20px;
  border-radius: 14px;
  background: var(--vp-c-brand-1);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
  text-decoration: none;
  /* 触摸设备上按下去不要有 300ms 延迟和高亮块 */
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

.gi-dl-meta {
  margin: 12px 0 0;
  font-size: 14px;
  color: var(--vp-c-text-2);
}
</style>