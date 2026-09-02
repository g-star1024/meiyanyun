// ============================================================
// 医疗合规引擎（咨询快捷开单前置校验）
// 两层防线：
//   1) 文案敏感词：咨询结论/方案说明命中医疗广告法违禁词 → 硬阻断提交（咨询师无诊疗承诺权）；
//   2) 禁忌-项目交叉：面诊禁忌阳性项 × 项目风险标签 → 阻断/强提醒。
// 禁忌阳性项必须填写医生备注；命中 BLOCK 的项目禁止进入方案单。
// 词库复用 useSensitiveWords（营销/海报/咨询共用，可后台维护追加）。
// ============================================================
import { checkSensitive } from './useSensitiveWords'
import type { ConsultContraindication, PlanItem } from '@/types/domain'

export interface ComplianceIssue {
  level: 'BLOCK' | 'WARN'
  text: string
}

/** 禁忌阳性项展示文案 */
export const CONTRA_FIELDS: { key: keyof Omit<ConsultContraindication, 'note'>; label: string }[] = [
  { key: 'pregnant', label: '妊娠期 / 哺乳期' },
  { key: 'allergy', label: '药物 / 麻醉 / 食物过敏史' },
  { key: 'scarConstitution', label: '瘢痕体质' },
  { key: 'skinLesion', label: '治疗区皮损 / 活动性炎症' },
  { key: 'coagulationAbn', label: '凝血异常 / 服用抗凝药' },
  { key: 'seriousIllness', label: '严重基础病（糖尿病/高血压/心脏病等）' },
]

export const RISK_TAG_LABEL: Record<string, string> = {
  INJECTION: '注射有创',
  LASER: '光电治疗',
  HIGH_ENERGY: '高能量抗衰',
  ANESTHESIA: '需表面麻醉',
  PREGNANCY_RISK: '孕期禁忌',
}

/**
 * 禁忌 × 项目风险标签交叉规则。
 * 返回 true = 硬阻断（该项目不得入方案）；'warn' = 强提醒（需医生审核重点确认）。
 */
function crossRisk(contra: ConsultContraindication, tags: string[]): 'block' | 'warn' | null {
  const has = (t: string) => tags.includes(t)
  if (contra.pregnant && (has('PREGNANCY_RISK') || has('LASER') || has('INJECTION'))) {
    return 'block' // 孕期/哺乳期：光电/注射类一律暂缓
  }
  if (contra.skinLesion && (has('LASER') || has('HIGH_ENERGY') || has('INJECTION'))) {
    return 'block' // 活动性炎症/皮损期禁光电与有创
  }
  if (contra.coagulationAbn && has('INJECTION')) {
    return 'block' // 凝血异常 + 注射 = 出血/血肿风险
  }
  if (contra.scarConstitution && (has('INJECTION') || has('HIGH_ENERGY'))) {
    return 'block' // 瘢痕体质慎做有创/高能抗衰
  }
  if (contra.allergy && has('ANESTHESIA')) {
    return 'block' // 麻醉相关过敏 + 需表麻项目
  }
  if (contra.allergy && has('INJECTION')) {
    return 'warn' // 过敏史 + 注射：需医生核对成分
  }
  if (contra.seriousIllness && (has('HIGH_ENERGY') || has('INJECTION'))) {
    return 'warn' // 严重基础病：医生评估耐受
  }
  if (contra.pregnant && !has('PREGNANCY_RISK')) {
    return 'warn'
  }
  return null
}

export function useCompliance() {
  /** 文案敏感词检查（硬阻断） */
  function checkText(text: string) {
    return checkSensitive(text || '')
  }

  /** 单项目与当前禁忌的交叉判定 */
  function checkItem(item: PlanItem, contra: ConsultContraindication): ComplianceIssue | null {
    const tags = item.riskTags ?? []
    const verdict = crossRisk(contra, tags)
    if (verdict === 'block') {
      return {
        level: 'BLOCK',
        text: `「${item.name}」与面诊禁忌冲突（${tags.map((t) => RISK_TAG_LABEL[t]).join('、')}），禁止加入方案，请医生面诊后改项`,
      }
    }
    if (verdict === 'warn') {
      return {
        level: 'WARN',
        text: `「${item.name}」需医生重点审核（${tags.map((t) => RISK_TAG_LABEL[t]).join('、')}），请在备注说明处置`,
      }
    }
    return null
  }

  /** 整个方案单合规扫描 */
  function checkPlan(
    items: PlanItem[],
    contra: ConsultContraindication,
    conclusion: string,
  ): { blocks: ComplianceIssue[]; warnings: ComplianceIssue[]; sensitive: string[]; canSubmit: boolean } {
    const blocks: ComplianceIssue[] = []
    const warnings: ComplianceIssue[] = []

    const sens = checkSensitive(conclusion || '')
    if (sens.hit) {
      blocks.push({ level: 'BLOCK', text: `方案说明含医疗广告违禁词：${sens.words.join('、')}（咨询师不得作疗效承诺）` })
    }

    for (const it of items) {
      const issue = checkItem(it, contra)
      if (issue?.level === 'BLOCK') blocks.push(issue)
      else if (issue?.level === 'WARN') warnings.push(issue)
    }

    const positive = CONTRA_FIELDS.filter((f) => contra[f.key]).map((f) => f.label)
    if (positive.length && !contra.note?.trim()) {
      blocks.push({ level: 'BLOCK', text: `面诊存在阳性禁忌项（${positive.join('、')}），必须填写医生备注/处置说明` })
    }

    return {
      blocks,
      warnings,
      sensitive: sens.words,
      canSubmit: blocks.length === 0,
    }
  }

  return { checkText, checkItem, checkPlan, CONTRA_FIELDS, RISK_TAG_LABEL }
}
