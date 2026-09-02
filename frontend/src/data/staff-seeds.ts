/* ============================================================
 * 门店工作人员种子数据
 * 供 ReceptionView / ConsultationView / AppointmentView 等页面共享
 * 字段：name 姓名 / role 角色 / specialty 专长领域 / status 在岗状态
 * ============================================================ */

export interface StaffMember {
  name: string
  role: '咨询师' | '医生' | '美容师' | '店长'
  specialty: string
  status: '在岗' | '休息' | '休假'
}

/* ---------- 咨询师 12 人 ---------- */
export const advisors: StaffMember[] = [
  { name: '苏晴',   role: '咨询师', specialty: '面部抗衰 · 微整',   status: '在岗' },
  { name: '吴桐',   role: '咨询师', specialty: '医美整形 · 鼻综合', status: '在岗' },
  { name: '李娜',   role: '咨询师', specialty: '皮肤管理 · 光电',   status: '在岗' },
  { name: '王琳',   role: '咨询师', specialty: '注射微整 · 线雕',   status: '在岗' },
  { name: '赵敏',   role: '咨询师', specialty: '面部年轻化 · 抗衰', status: '在岗' },
  { name: '周敏',   role: '咨询师', specialty: '综合方案 · 客情',   status: '在岗' },
  { name: '陈雨晴', role: '咨询师', specialty: '双眼皮 · 眼综合',   status: '在岗' },
  { name: '林晓',   role: '咨询师', specialty: '形体雕塑 · 吸脂',   status: '在岗' },
  { name: '何佳仪', role: '咨询师', specialty: '口腔 · 正畸',       status: '在岗' },
  { name: '张雨桐', role: '咨询师', specialty: '私密 · 产后修复',   status: '休息' },
  { name: '周雨晴', role: '咨询师', specialty: '纹绣 · 半永久',     status: '在岗' },
  { name: '孙雪萍', role: '咨询师', specialty: '身体管理 · 纤体',   status: '在岗' },
  { name: '杨雨欣', role: '咨询师', specialty: '综合方案 · VIP 客户', status: '在岗' },
]

/* ---------- 医生 10 人 ---------- */
export const doctors: StaffMember[] = [
  { name: '张薇',   role: '医生', specialty: '整形外科 · 面部',   status: '在岗' },
  { name: '李悦',   role: '医生', specialty: '皮肤科 · 光电',     status: '在岗' },
  { name: '王敏',   role: '医生', specialty: '注射微整 · 肉毒',   status: '在岗' },
  { name: '刘芳',   role: '医生', specialty: '整形外科 · 鼻综合', status: '在岗' },
  { name: '陈琳',   role: '医生', specialty: '眼科 · 双眼皮',     status: '在岗' },
  { name: '黄雪琴', role: '医生', specialty: '皮肤科 · 激光',     status: '在岗' },
  { name: '赵静',   role: '医生', specialty: '口腔 · 种植',       status: '在岗' },
  { name: '周丽华', role: '医生', specialty: '整形外科 · 形体',   status: '休假' },
  { name: '吴佳',   role: '医生', specialty: '皮肤 · 水光中胚层', status: '在岗' },
  { name: '杨晓华', role: '医生', specialty: '整形外科 · 面部综合', status: '在岗' },
]

/* ---------- 美容师 12 人 ---------- */
export const beauticians: StaffMember[] = [
  { name: '周敏',   role: '美容师', specialty: '皮肤护理 · 面部拨筋', status: '在岗' },
  { name: '王萌',   role: '美容师', specialty: '美甲美睫 · 半永久',   status: '在岗' },
  { name: '陈晓',   role: '美容师', specialty: '面部 SPA · 修护',     status: '在岗' },
  { name: '李婷',   role: '美容师', specialty: '脱毛 · 体毛管理',     status: '在岗' },
  { name: '张薇',   role: '美容师', specialty: '皮肤检测 · Visia',    status: '在岗' },
  { name: '刘芳',   role: '美容师', specialty: '头部 SPA · 头疗',     status: '在岗' },
  { name: '黄莉',   role: '美容师', specialty: '面部拨筋 · 刮痧',     status: '休息' },
  { name: '钱欣怡', role: '美容师', specialty: '纹绣 · 眉眼唇',       status: '在岗' },
  { name: '蒋璐',   role: '美容师', specialty: '美体 · 淋巴排毒',     status: '在岗' },
  { name: '秦雯',   role: '美容师', specialty: '皮肤护理 · 敏感肌',   status: '在岗' },
  { name: '宋雅',   role: '美容师', specialty: '综合护理 · 疗程管理', status: '在岗' },
  { name: '唐雨萱', role: '美容师', specialty: '身体护理 · 纤体',     status: '在岗' },
]

/* ---------- 店长 5 人 ---------- */
export const managers: StaffMember[] = [
  { name: '陈雅琳', role: '店长', specialty: '综合管理',     status: '在岗' },
  { name: '何雨桐', role: '店长', specialty: '运营管理',     status: '在岗' },
  { name: '林悦',   role: '店长', specialty: '客户关系管理', status: '在岗' },
  { name: '周颖',   role: '店长', specialty: '财务 · 合规',  status: '在岗' },
  { name: '杨雨欣', role: '店长', specialty: '培训 · 团队',  status: '休假' },
]

/* ---------- 按角色索引 ---------- */
export function getAdvisers(): StaffMember[] {
  return advisors.filter(s => s.status !== '休假')
}

export function getDoctors(): StaffMember[] {
  return doctors.filter(s => s.status !== '休假')
}

export function getBeauticians(): StaffMember[] {
  return beauticians.filter(s => s.status !== '休假')
}

export function getManagers(): StaffMember[] {
  return managers.filter(s => s.status !== '休假')
}

export function getAllStaff(): StaffMember[] {
  return [...advisors, ...doctors, ...beauticians, ...managers]
}
