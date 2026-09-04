import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// ============================================================
// 审计日志 store（M1 集团管控 / 审计日志）
// - 跨模块操作留痕：登录、退款/退卡审批、权限变更、合同签署、病历修改/修订、
//   资产转移、投诉结案、库存调整、配置变更、数据导出
// - 不可删除/不可修改（append-only），支持按模块/操作人/风险等级/时间筛选
// - 高风险操作标红；每条记录含操作人、IP、模块、动作、对象、前后摘要
// ============================================================

export type AuditRisk = 'HIGH' | 'MEDIUM' | 'LOW'
export type AuditModule =
  | 'AUTH' | 'FINANCE' | 'RBAC' | 'CONTRACT' | 'EMR' | 'ASSET'
  | 'COMPLAINT' | 'INVENTORY' | 'SETTINGS' | 'DATA' | 'MARKETING'

export const MODULE_LABEL: Record<AuditModule, string> = {
  AUTH: '登录鉴权', FINANCE: '财务退款', RBAC: '权限角色', CONTRACT: '合同管理',
  EMR: '电子病历', ASSET: '客户资产', COMPLAINT: '投诉处理', INVENTORY: '库存采购',
  SETTINGS: '系统设置', DATA: '数据导出', MARKETING: '营销活动',
}
export const RISK_LABEL: Record<AuditRisk, string> = { HIGH: '高风险', MEDIUM: '中风险', LOW: '常规' }

export interface AuditEntry {
  id: string
  at: string
  actor: string
  actorRole: string
  module: AuditModule
  action: string
  target: string
  risk: AuditRisk
  ip: string
  result: 'SUCCESS' | 'FAILED'
  before?: string
  after?: string
  detail: string
}

let _cid = 0
function cid() { _cid += 1; return `aud-${Date.now().toString(36)}-${_cid}` }
function hoursAgo(h: number) { return new Date(Date.now() - h * 3600000).toISOString() }

export const useM1AuditStore = defineStore('m1Audit', () => {
  const logs = ref<AuditEntry[]>([])
  const seeded = ref(false)

  function add(e: Omit<AuditEntry, 'id'>) {
    logs.value.unshift({ ...e, id: cid() })
  }

  const stats = computed(() => {
    const last24 = logs.value.filter((e) => Date.now() - new Date(e.at).getTime() < 86400000)
    return {
      total: logs.value.length,
      high: logs.value.filter((e) => e.risk === 'HIGH').length,
      failed: logs.value.filter((e) => e.result === 'FAILED').length,
      last24: last24.length,
      actors: new Set(logs.value.map((e) => e.actor)).size,
    }
  })

  function seed() {
    if (seeded.value) return
    const mk = (e: Omit<AuditEntry, 'id'>): AuditEntry => ({ ...e, id: cid() })
    logs.value = [
      mk({ at: hoursAgo(0.5), actor: '苏晴', actorRole: '门店店长', module: 'FINANCE', action: '退款审批通过', target: '退款单 RF20260825001', risk: 'HIGH', ip: '10.12.12.7', result: 'SUCCESS', before: '待审批 ¥12,800', after: '已通过（双签）', detail: '客户「周童」热玛吉未消费退款，门店+财务双签通过' }),
      mk({ at: hoursAgo(1), actor: '周岚', actorRole: '集团管理员', module: 'RBAC', action: '修改角色字段权限', target: '角色「皮肤科护士」', risk: 'MEDIUM', ip: '10.12.21.45', result: 'SUCCESS', before: 'emr.treatment: HIDE', after: 'emr.treatment: READ', detail: '字段级权限调整，即时生效于 15 名护士' }),
      mk({ at: hoursAgo(2), actor: '顾屿', actorRole: '医生', module: 'EMR', action: '修订病历', target: 'EMR20260818007-R2', risk: 'MEDIUM', ip: '10.12.12.31', result: 'SUCCESS', before: '诊断：面部光老化（v1）', after: '诊断：面部光老化伴色斑（v2）', detail: '已签名病历修订，原版本归档保留，需二次签名' }),
      mk({ at: hoursAgo(3), actor: '陈野', actorRole: '区域经理', module: 'ASSET', action: '资产转移审批通过', target: '转移单 TR20260824002', risk: 'HIGH', ip: '10.12.34.12', result: 'SUCCESS', before: '赠卡余额 ¥3,200（客户A）', after: '转移至客户B（同门店）', detail: '赠送金转移 ¥3,200，扣减来源、双向流水已记录' }),
      mk({ at: hoursAgo(4), actor: '夏沫', actorRole: '前台/收银', module: 'AUTH', action: '登录', target: '门店前台终端-03', risk: 'LOW', ip: '10.12.12.10', result: 'SUCCESS', detail: '账号密码登录，门店 静安旗舰店' }),
      mk({ at: hoursAgo(5), actor: '未知', actorRole: '—', module: 'AUTH', action: '登录失败', target: '账号 qian.jin', risk: 'MEDIUM', ip: '203.0.113.44', result: 'FAILED', detail: '密码错误连续 5 次，账号已临时锁定 30 分钟（异地 IP）' }),
      mk({ at: hoursAgo(6), actor: '林微', actorRole: '咨询师', module: 'COMPLAINT', action: '投诉结案', target: '投诉 TS20260820003', risk: 'MEDIUM', ip: '10.12.12.22', result: 'SUCCESS', before: '处理中', after: '已关闭（赔付 ¥800）', detail: '客户对效果不满，协商补偿一次光子嫩肤，客户确认满意' }),
      mk({ at: hoursAgo(8), actor: '钱进', actorRole: '财务', module: 'CONTRACT', action: '合同退款估算', target: '合同 HT20260712009', risk: 'LOW', ip: '10.12.34.18', result: 'SUCCESS', before: '冷静期内（全额退）', after: '超冷静期 12 天，扣违约金 20%', detail: '退款估算 ¥15,840（原 ¥19,800），按合同 penaltyRate 计算' }),
      mk({ at: hoursAgo(10), actor: '白桥', actorRole: '运营', module: 'MARKETING', action: '活动中止', target: '活动「保妥适拼团8.5折」', risk: 'MEDIUM', ip: '10.12.12.55', result: 'SUCCESS', before: '进行中', after: '已取消', detail: '因供应商限价政策中止，已通知 0 名已下单客户' }),
      mk({ at: hoursAgo(12), actor: '周岚', actorRole: '集团管理员', module: 'SETTINGS', action: '修改双签阈值', target: '系统设置·L2 阈值', risk: 'HIGH', ip: '10.12.21.45', result: 'SUCCESS', before: 'L2=¥50,000', after: 'L2=¥30,000', detail: '审批层级阈值调整，影响 3 家在途审批单' }),
      mk({ at: hoursAgo(14), actor: '陈野', actorRole: '区域经理', module: 'DATA', action: '导出经营报表', target: '华东大区 7 月经营明细.xlsx', risk: 'MEDIUM', ip: '10.12.34.12', result: 'SUCCESS', detail: '导出含成本/毛利字段（finance:margin:view），1,284 行' }),
      mk({ at: hoursAgo(18), actor: '苏晴', actorRole: '门店店长', module: 'INVENTORY', action: '入库登记', target: '采购单 PO20260818003', risk: 'LOW', ip: '10.12.12.7', result: 'SUCCESS', before: '润致娃娃针 库存 12', after: '润致娃娃针 库存 62', detail: '入库 50 支，批次 BHX0825，质检合格' }),
      mk({ at: hoursAgo(26), actor: '周岚', actorRole: '集团管理员', module: 'AUTH', action: '代操作（impersonate）', target: '苏晴（静安店长）', risk: 'HIGH', ip: '10.12.21.45', result: 'SUCCESS', detail: '开始代操作，理由：处理工单#T20260824 审批异常；会话 47 分钟后结束' }),
      mk({ at: hoursAgo(30), actor: '顾屿', actorRole: '医生', module: 'EMR', action: '查看敏感信息', target: '客户 138****6677 身份证号', risk: 'MEDIUM', ip: '10.12.12.31', result: 'SUCCESS', detail: '解密查看身份证号（emr:edit 授权），操作已留痕' }),
      mk({ at: hoursAgo(40), actor: '张强', actorRole: '—', module: 'AUTH', action: '越权访问拦截', target: '/admin/permissions', risk: 'HIGH', ip: '10.12.12.77', result: 'FAILED', detail: '前台角色尝试访问权限矩阵页，缺少 permission:view，已拦截并记录' }),
    ]
    seeded.value = true
  }

  return { logs, MODULE_LABEL, RISK_LABEL, stats, add, seed }
})
