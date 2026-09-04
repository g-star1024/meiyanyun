// ============================================================
// 字段级权限（HIDE/MASK/READ/EDIT）规划草案配置
// - 字段级管控后端能力尚未落地：本文件仅为展示草案，任何修改不落库、不生效。
// - 权限矩阵页（/admin/permissions）「字段级权限」tab 只读引用；
//   后端字段级能力上线后，本文件应由真实端点数据替换。
// ============================================================

export type FieldAccess = 'HIDE' | 'MASK' | 'READ' | 'EDIT'

export const FIELD_ACCESS_LABEL: Record<FieldAccess, string> = {
  HIDE: '隐藏', MASK: '脱敏', READ: '只读', EDIT: '可编辑',
}
export const FIELD_ACCESS_ORDER: FieldAccess[] = ['HIDE', 'MASK', 'READ', 'EDIT']

export interface FieldDef { key: string; label: string; desc?: string }
export const FIELD_GROUPS: { module: string; fields: FieldDef[] }[] = [
  {
    module: '客户',
    fields: [
      { key: 'customer.phone', label: '手机号', desc: '完整手机号属敏感个人信息' },
      { key: 'customer.idCard', label: '身份证号', desc: '法定敏感信息' },
      { key: 'customer.address', label: '联系地址' },
      { key: 'customer.consumption', label: '累计消费金额' },
    ],
  },
  {
    module: '订单 / 财务',
    fields: [
      { key: 'order.cost', label: '成本价', desc: '仅财务可见（finance:margin:view）' },
      { key: 'order.margin', label: '毛利', desc: '仅财务可见' },
      { key: 'order.commission', label: '提成金额' },
      { key: 'order.payment', label: '支付流水号' },
    ],
  },
  {
    module: '病历 / 医疗',
    fields: [
      { key: 'emr.diagnosis', label: '诊断结论' },
      { key: 'emr.allergy', label: '过敏史' },
      { key: 'emr.treatment', label: '治疗方案' },
      { key: 'emr.photo', label: '术前术后照片' },
    ],
  },
  {
    module: '员工',
    fields: [
      { key: 'staff.salary', label: '薪资' },
      { key: 'staff.performance', label: '业绩明细' },
      { key: 'staff.contract', label: '合同信息' },
    ],
  },
]
export const ALL_FIELDS: FieldDef[] = FIELD_GROUPS.flatMap((g) => g.fields)

// 内置角色的字段访问策略【规划草案】：后端字段级能力落地前仅只读展示，不代表实际生效
export const BUILTIN_FIELD_DEFAULTS: Record<string, Partial<Record<string, FieldAccess>>> = {
  SUPER_ADMIN: Object.fromEntries(ALL_FIELDS.map((f) => [f.key, 'EDIT' as FieldAccess])),
  REGION_MGR: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  STORE_MGR: { 'customer.phone': 'MASK', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  CONSULTANT: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ' },
  DOCTOR: { 'customer.phone': 'MASK', 'emr.diagnosis': 'EDIT', 'emr.allergy': 'EDIT', 'emr.treatment': 'EDIT', 'emr.photo': 'EDIT' },
  FRONT_DESK: { 'customer.phone': 'READ', 'customer.address': 'READ', 'order.payment': 'READ' },
  OPERATOR: { 'customer.phone': 'MASK', 'customer.consumption': 'READ' },
  FINANCE: { 'customer.phone': 'MASK', 'order.cost': 'EDIT', 'order.margin': 'EDIT', 'order.payment': 'READ', 'order.commission': 'READ' },
}

/** 取某内置角色对某字段的草案访问级别；未配置视为隐藏 */
export function fieldAccessOf(roleCode: string, fieldKey: string): FieldAccess {
  return BUILTIN_FIELD_DEFAULTS[roleCode]?.[fieldKey] ?? 'HIDE'
}
