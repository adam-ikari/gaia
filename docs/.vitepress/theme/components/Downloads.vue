<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

// 取**本仓库**的 latest Release —— 应用仓库是私有的,那里的下载链接需要登录态,
// 所以正式包挂在公开的站点仓库上。
const REPO = 'adam-ikari/gaia'

type Asset = { name: string; size: number; browser_download_url: string }

const state = ref<'loading' | 'ready' | 'none' | 'error'>('loading')
const tag = ref('')
const assets = ref<Asset[]>([])

const rows = computed(() =>
  assets.value.map((a) => ({ name: a.name, url: a.browser_download_url, fit: fit(a.name), size: mb(a.size) }))
)

function fit(name: string) {
  if (/release/i.test(name)) return '正式版，推荐；与调试包同一签名，可直接覆盖安装升级'
  if (/debug/i.test(name)) return '调试版，功能相同、体积略大；需要 adb 调试时用它'
  return '盖亚输入法安装包'
}

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
    assets.value = data.assets ?? []
    state.value = assets.value.length ? 'ready' : 'none'
  } catch {
    state.value = 'error'
  }
})
</script>

<template>
  <div class="gi-dl">
    <p v-if="state === 'loading'">正在读取发布信息…</p>

    <p v-else-if="state === 'none'">
      公开下载包尚未发布。发布后这里会列出正式版与调试版两个包。
    </p>

    <p v-else-if="state === 'error'">无法连接 GitHub 获取发布信息，请稍后刷新重试。</p>

    <template v-else>
      <p>当前版本 <strong>{{ tag }}</strong>，按需取用：</p>
      <table>
        <thead>
          <tr>
            <th>文件</th>
            <th>说明</th>
            <th>大小</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.name">
            <td><a :href="r.url">{{ r.name }}</a></td>
            <td>{{ r.fit }}</td>
            <td>{{ r.size }}</td>
          </tr>
        </tbody>
      </table>
    </template>

    <p class="gi-dl-note">
      两个包由同一份源码构建、同一证书签名，<strong>可以互相覆盖安装</strong>，放心换包。
      安装后需在系统设置里启用并切换为默认输入法，步骤见本页下方。
    </p>
  </div>
</template>

<style scoped>
.gi-dl {
  margin: 24px 0;
}

.gi-dl-note {
  margin-top: 16px;
  font-size: 13px;
  color: var(--vp-c-text-2);
}
</style>
