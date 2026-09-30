<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

/**
 * 下载表。数据源是**本仓库**的 latest Release —— 应用仓库是私有的，
 * 那边的下载链接需要登录态，访客拿不到，所以包挂在公开的站点仓库上。
 *
 * 站点只提供正式版：调试包不进这张表（也不发布到本仓库）。
 */
const REPO = 'adam-ikari/gaia'

type Asset = { name: string; size: number; browser_download_url: string; download_count?: number }

const state = ref<'loading' | 'ready' | 'none' | 'error'>('loading')
const tag = ref('')
const assets = ref<Asset[]>([])

const rows = computed(() => assets.value.map((a) => ({ ...a, size: mb(a.size) })))

function mb(size: number) {
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

onMounted(async () => {
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`)
    if (res.status === 404) {
      state.value = 'none'
      return
    }
    if (!res.ok) throw new Error(String(res.status))
    const data = await res.json()
    tag.value = data.tag_name ?? ''
    // 只认 release 包；防御性过滤，万一以后传错了也不会露出来
    assets.value = (data.assets ?? []).filter((a: Asset) => /release/i.test(a.name))
    state.value = assets.value.length ? 'ready' : 'none'
  } catch {
    state.value = 'error'
  }
})
</script>

<template>
  <div class="gi-dl">
    <p v-if="state === 'loading'">正在读取发布信息…</p>

    <p v-else-if="state === 'none'">下载包正在准备，稍后再来。</p>

    <p v-else-if="state === 'error'">无法连接 GitHub 获取发布信息，请稍后刷新重试。</p>

    <template v-else>
      <p>当前版本 <strong>{{ tag }}</strong>：</p>
      <table>
        <thead>
          <tr>
            <th>文件</th>
            <th>大小</th>
            <th>下载</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.name">
            <td>
              <a class="gi-dl-btn" :href="r.browser_download_url">下载 {{ r.name }}</a>
            </td>
            <td>{{ r.size }}</td>
            <td>{{ r.download_count ?? 0 }} 次</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.gi-dl {
  margin: 0 auto;
  max-width: 560px;
}

.gi-dl-btn {
  display: inline-block;
  padding: 9px 20px;
  border-radius: 980px;
  background: var(--vp-c-brand-1);
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
}

.gi-dl-btn:hover {
  background: var(--vp-c-brand-1);
  opacity: 0.85;
}
</style>
