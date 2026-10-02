/**
 * 从截图里裁出输入法状态行，产出站点用的 status-row.png。
 *
 * ## 为什么裁
 *
 * 站点要展示的是**候选行**，不是整屏。实测整屏里属于输入法的只有最底下那条黑边：
 * 1440px 里 96px，占 6.7%。其余是实验场（调试页），摆出来等于告诉访客
 * 「这是我们的测试页面」。裁这一条，是全站唯一一张真图。
 *
 * ## 为什么要检测而不是写死坐标
 *
 * 黑边上沿**不固定**：候选有多少、L4 有几把键、状态行处于两态中的哪一态，
 * 都会改变它。实测同一轮 9 张图里出现过 1344 / 1393 / 1401 三种上沿。
 *
 * ## 为什么必须验(两处,各挡一类坏图)
 *
 * 一、**上沿检测**:简单算法(自底往上找第一个暗行)在两种图上会**静默给出错误答案**:
 * - 设置页是深色整屏 → 报「黑条高 1439px」
 * - 许可页没有输入法条、整屏都亮 → 报「黑条高 0px」
 *
 * 二、**内容检测**:上沿找对了不等于里面有东西。空条(只剩两侧箭头和分隔线、
 * 一个候选都没有)照样能裁成一张 1440×95 的图,推上站点就是一条黑边 ——
 * 看不出错,但也没信息。见 [measureContrast] 的判据说明。
 *
 * 两处都是失败即报错退出、让 CI 红 —— 不能让一张坏图悄悄进站点
 * (这正是之前那个播不了的 mp4 的教训:截图全绿、退出码 0,没人发现问题)。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

/** 用 ImageMagick 把图压成「每行一个灰度值」，用来找黑边上沿。 */
function rowGrays(file) {
  const raw = execFileSync(
    'convert',
    [file, '-colorspace', 'Gray', '-scale', '1x1000!', '-depth', '8', 'pgm:-'],
    { maxBuffer: 64 * 1024 * 1024 }
  )
  const headerEnd = raw.indexOf(Buffer.from('255\n')) + 4
  const dims = raw.toString('latin1', raw.indexOf('\n') + 1, raw.indexOf('255\n')).trim()
  const [, h] = dims.split(/\s+/).map(Number)
  return raw.subarray(headerEnd, headerEnd + h)
}

/** 图像实际宽高：直接读 PNG 的 IHDR，不用解码。 */
function pngSize(file) {
  const b = readFileSync(file)
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }
}

/**
 * 找出底部那条暗带的上沿。
 * @returns {{top:number,height:number}|null}
 */
export function findStatusBar(file) {
  const { h: height } = pngSize(file)
  const grays = rowGrays(file)
  const rows = height
  // 灰度图被压到 1000 行，按比例映射回原图
  const at = (y) => grays[Math.min(grays.length - 1, Math.round((y / rows) * grays.length))]

  let y = rows - 1
  while (y > 0 && at(y) <= 40) y--
  const top = y + 1
  const barHeight = rows - top

  // 合理性检查：状态行不可能顶天，也不可能只有几像素
  if (barHeight < 48 || barHeight > rows * 0.5) return null
  if (top < rows * 0.5) return null
  return { top, height: barHeight }
}

/**
 * 暗带里到底画了东西没有。
 *
 * ## 判据用**标准差**,不用最亮值(2026-10-02 修)
 *
 * 原来判的是 `%[maxima] > 40`。两个毛病:
 * 1. **ImageMagick 7 的 `%[maxima]` 返回的是量子值(0–65535),不是 0–255** ——
 *    所以 `> 40` 实际等价于「非零」,几乎任何东西都能过。实测一张**空条**
 *    (只剩两侧箭头和分隔线、一个候选都没有)的 maxima 是 9475,顺利通过。
 * 2. 即便换成 0–255,「有箭头」也不等于「有候选」——箭头一直在。
 *
 * 标准差量的是**局部反差**:一条画了字/键帽的条,相邻像素忽明忽暗;
 * 空条除了几条暗线几乎均匀。实测(1440×95 的条):
 *
 * ```
 *   空条(04-page-swipe)   0.0074   ← 只有箭头和分隔线
 *   数字框(06)            0.0608
 *   空闲态(01)            0.1127
 *   组词态(02)            0.1218
 * ```
 *
 * 差一个数量级,而且**与主题明暗无关**(反差大小不取决于底色亮不亮),
 * 这是选它而不是选「亮像素占比」的原因 —— 后者在浅色主题下整条都亮,失去区分力。
 *
 * @returns {{sd:number}|null} sd 低于阈值返回 null,调用方据此报错
 */
const MIN_CONTRAST = 0.02

function measureContrast(file, top, height) {
  const { w } = pngSize(file)
  const sd = execFileSync(
    'convert',
    [file, '-crop', `${w}x${height}+0+${top}`, '+repage', '-colorspace', 'Gray',
      '-format', '%[fx:standard_deviation]', 'info:'],
    { encoding: 'utf8' }
  ).trim()
  return { sd: Number(sd) }
}

const [, , input, output] = process.argv
if (!input || !output) {
  console.error('用法:node crop-status-row.mjs <输入.png> <输出.png>')
  process.exit(2)
}
if (!existsSync(input)) {
  console.error(`裁剪失败:输入不存在 ${input}`)
  process.exit(1)
}

const bar = findStatusBar(input)
if (!bar) {
  console.error('裁剪失败:没找到底部状态行(整屏同色?这张图可能没有输入法条)')
  process.exit(1)
}
const { sd } = measureContrast(input, bar.top, bar.height)
if (!(sd >= MIN_CONTRAST)) {
  console.error(
    `裁剪失败:这条只有箭头和分隔线,没画内容(标准差 ${sd.toFixed(4)} < ${MIN_CONTRAST})—— ` +
      '输入法没画东西,或者这一帧它本来就是空的。换一张源图,别拿空条去站点。'
  )
  process.exit(1)
}

const { w } = pngSize(input)
execFileSync('convert', [input, '-crop', `${w}x${bar.height}+0+${bar.top}`, '+repage', output])
console.log(`输入法条 ${w}x${bar.height} @y=${bar.top} 反差=${sd.toFixed(4)} → ${output}`)
