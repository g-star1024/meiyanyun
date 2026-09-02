import { ref } from 'vue'

/**
 * A1-04 敏感词 / 违禁词校验（轻量版）
 * 营销活动、推送文案、券名、海报文案、落地页提交前必须过 check()。
 * 真实环境词库由后端下发，这里内置一份演示词库，可在营销设置页维护追加词。
 */

// 医疗广告法 / 平台违禁词（演示词库）
const BUILTIN_WORDS = [
  // 绝对化用语
  '最佳', '最好', '最优', '最高级', '第一', '唯一', '顶级', '极品', '绝无仅有', '万能',
  '100%', '百分百', '永久', '彻底', '根治', '治愈', '无效退款', '永不复发',
  // 医疗夸大/承诺疗效
  '包治', '药到病除', '一次见效', '即刻见效', '无副作用', '安全无副作用',
  '国家级', '央视推荐', '特供', '专供',
  // 金融诱导
  '稳赚', '零风险', '高收益', '保本',
]

const customWords = ref<string[]>([])
const seeded = ref(false)

function seed() {
  if (seeded.value) return
  // 从 localStorage 读取用户追加词（演示持久化）
  try {
    const saved = localStorage.getItem('m5:banned-words')
    if (saved) customWords.value = JSON.parse(saved)
  } catch { /* ignore */ }
  seeded.value = true
}

function allWords() {
  seed()
  return [...BUILTIN_WORDS, ...customWords.value]
}

export interface CheckResult {
  hit: boolean
  words: string[]
  message: string
}

/** 检查文本是否含违禁词，返回命中词列表 */
export function checkSensitive(text: string): CheckResult {
  if (!text) return { hit: false, words: [], message: '' }
  const hits: string[] = []
  for (const w of allWords()) {
    if (w && text.includes(w)) hits.push(w)
  }
  return {
    hit: hits.length > 0,
    words: hits,
    message: hits.length ? `文案含违禁词：${hits.join('、')}，请修改后再提交` : '',
  }
}

/** 批量检查多条文本，返回所有命中词 */
export function checkAny(...texts: string[]): CheckResult {
  const allHits = new Set<string>()
  for (const t of texts) {
    const r = checkSensitive(t)
    r.words.forEach((w) => allHits.add(w))
  }
  const words = [...allHits]
  return { hit: words.length > 0, words, message: words.length ? `文案含违禁词：${words.join('、')}` : '' }
}

export function useSensitiveWords() {
  seed()
  function addWord(word: string) {
    const w = word.trim()
    if (w && !customWords.value.includes(w) && !BUILTIN_WORDS.includes(w)) {
      customWords.value.push(w)
      persist()
    }
  }
  function removeWord(word: string) {
    const i = customWords.value.indexOf(word)
    if (i >= 0) { customWords.value.splice(i, 1); persist() }
  }
  function persist() {
    try { localStorage.setItem('m5:banned-words', JSON.stringify(customWords.value)) } catch { /* ignore */ }
  }
  return {
    builtinWords: BUILTIN_WORDS,
    customWords,
    check: checkSensitive,
    checkAny,
    addWord,
    removeWord,
  }
}
