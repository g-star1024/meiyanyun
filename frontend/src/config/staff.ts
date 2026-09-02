// 可分配员工（演示用种子；真实环境来自 staff 服务 / 集团组织架构）
// 工号与后端种子对齐：E001-E014 为 org 服务种子（dev-login 真源），
// SE101-SE105 为 RbacDataInitializer 追加员工；staff-* 为离线演示 mock 保留。
export interface StaffSeed {
  id: string
  name: string
  title: string
}

// 运行时真实花名册（GET /api/org/staff 实测，18096 实例）
export const ROSTER: StaffSeed[] = [
  { id: 'E001', name: '刘治疗师', title: '治疗师' },
  { id: 'E002', name: '王前台', title: '前台/收银' },
  { id: 'E003', name: '陈医生', title: '执业医师' },
  { id: 'E004', name: '赵咨询师', title: '咨询师' },
  { id: 'E005', name: '李店长', title: '门店店长' },
  { id: 'E006', name: '周收银', title: '前台/收银' },
  { id: 'E007', name: '钱店长', title: '门店店长' },
  { id: 'E008', name: '孙医生', title: '执业医师' },
  { id: 'E009', name: '吴店长', title: '门店店长' },
  { id: 'E010', name: '郑医生', title: '执业医师' },
  { id: 'E011', name: '冯区域', title: '区域经理' },
  { id: 'E012', name: '褚财务总监', title: '财务总监' },
  { id: 'E013', name: '卫运营', title: '运营' },
  { id: 'E014', name: '蒋IT', title: '集团管理员' },
  { id: 'SE101', name: '周岚', title: '集团管理员' },
  { id: 'SE102', name: '陈野', title: '区域经理' },
  { id: 'SE103', name: '白桥', title: '运营' },
  { id: 'SE104', name: '钱进', title: '财务' },
  { id: 'SE105', name: '夏沫', title: '前台/收银' },
]

export const ADVISORS: StaffSeed[] = [
  { id: 'E004', name: '赵咨询师', title: '咨询师' },
  { id: 'E005', name: '李店长', title: '店长' },
  // 离线演示 mock 保留
  { id: 'staff-lin', name: '林微', title: '咨询师' },
  { id: 'staff-su', name: '苏晴', title: '店长' },
]

// 仅列执业资质医生（medicalLicensed=true）：治疗师等无资质 DOCTOR 不入选
export const DOCTORS: StaffSeed[] = [
  { id: 'E003', name: '陈医生', title: '主治医师' },
  // 离线演示 mock 保留
  { id: 'staff-gu', name: '顾屿', title: '主治医师' },
]

export const ALL_STAFF: StaffSeed[] = [...ROSTER, ...ADVISORS, ...DOCTORS]

export function staffName(id?: string): string {
  if (!id) return '—'
  return ALL_STAFF.find((s) => s.id === id)?.name || id
}
