/**
 * AI 视觉判卷:对场景截屏提问(多模态模型),把回答写回 report.json。
 *
 * 环境变量(OpenAI 兼容协议):
 *   AITEST_API_KEY   API Key(未设置则跳过判卷,证据仍完整保留供人工/代理审阅)
 *   AITEST_BASE_URL  默认 https://open.bigmodel.cn/api/paas/v4
 *   AITEST_MODEL     默认 glm-4v-flash(免费多模态)
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.AITEST_BASE_URL ?? 'https://open.bigmodel.cn/api/paas/v4'
const MODEL = process.env.AITEST_MODEL ?? 'glm-4v-flash'

async function askVision(apiKey, question, imageFile) {
  const b64 = readFileSync(imageFile).toString('base64')
  const res = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({
      model: MODEL,
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image_url', image_url: { url: `data:image/png;base64,${b64}` } },
            { type: 'text', text: question },
          ],
        },
      ],
      temperature: 0.1,
      max_tokens: 300,
    }),
  })
  if (!res.ok) throw new Error(`vision API ${res.status}: ${(await res.text()).slice(0, 200)}`)
  const data = await res.json()
  return data.choices?.[0]?.message?.content ?? '(空回答)'
}

/** 遍历 report.scenarios 的 judge 定义,逐张截图判卷并回写报告。 */
export async function judgeEvidence(report, runDir, device) {
  const apiKey = process.env.AITEST_API_KEY
  const jobs = []
  for (const sc of report.scenarios) {
    const def = (await import('./scenarios.mjs')).scenarios.find((s) => s.name === sc.name)
    if (!def?.judge) continue
    const list = Array.isArray(def.judge) ? def.judge : [def.judge]
    for (const j of list) jobs.push({ scenario: sc.name, ...j })
  }
  if (jobs.length === 0) return
  if (!apiKey) {
    console.log('\n(AITEST_API_KEY 未设置 → AI 视觉判卷跳过,截图证据已保存,可人工/代理审阅)')
    report.judge = jobs.map((j) => ({ scenario: j.scenario, shot: j.shot, status: 'skipped' }))
    writeFileSync(join(runDir, 'report.json'), JSON.stringify(report, null, 2))
    return
  }

  report.judge = []
  for (const j of jobs) {
    const img = join(runDir, j.scenario, `${j.shot}.png`)
    if (!existsSync(img)) { report.judge.push({ ...j, status: 'no-image' }); continue }
    process.stdout.write(`👁 AI 判卷 ${j.scenario}/${j.shot} ... `)
    try {
      const answer = await askVision(apiKey, `${j.question}\n只依据截图回答,50 字内。`)
      console.log(answer.replace(/\n/g, ' '))
      report.judge.push({ scenario: j.scenario, shot: j.shot, question: j.question, answer, status: 'done' })
    } catch (e) {
      console.log(`ERROR ${e.message}`)
      report.judge.push({ scenario: j.scenario, shot: j.shot, status: 'error', error: e.message })
    }
  }
  writeFileSync(join(runDir, 'report.json'), JSON.stringify(report, null, 2))
}
