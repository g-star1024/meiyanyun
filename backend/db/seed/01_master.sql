-- ============================================================
-- 美研云门店中台 · 独立测试库 meiyun_seed 种子数据 · 01 主数据
-- ------------------------------------------------------------
-- 约定（与真实库契约一致，见 meiyun-dev-rules 铁律）：
--   * 枚举一律中文（CHECK 约束）；channel 存英文 key，前端字典转中文。
--   * 金额：txn_order.amount / member_card.balance 等 bigint 存「分」；
--           customer.total_spend decimal(12,2) 存「元」；points 存积分。
--   * ID 隔离前缀：门店 SST、员工 SE、标签 STG、客户 SC（与现有 M/ST/E/TG 零冲突）。
-- 幂等：本脚本仅在业务表已 TRUNCATE 的 meiyun_seed 上执行（由 setup-seed-db.sh 保证）。
-- ============================================================

-- ---------- 租户 ----------
INSERT INTO tenant (tenant_id, tenant_name, brand, status) VALUES
  ('T001', '美颜集团（种子）', '美颜·', '在用');

-- ---------- 会员等级（cnt=升级累计消费门槛/元，discount=折扣） ----------
INSERT INTO member_level (level, cnt, discount) VALUES
  ('普通', 29160, 1.00),
  ('银卡', 11664, 0.95),
  ('金卡',  5346, 0.90),
  ('钻石', 1944, 0.85),
  ('黑卡',  486, 0.80);

-- ---------- 积分规则 / 营销配置（单行配置表） ----------
INSERT INTO point_rule (rule_id, earn_rate, redeem_ratio, expire_months) VALUES
  (1, 1.00, 100.00, 12);
INSERT INTO marketing_cfg (cfg_id, referral_arrived_reward, referral_deal_reward, commission_rate, weekly_push_limit) VALUES
  (1, 200, 350, 0.05, 3);

-- ---------- 门店（6 家，覆盖华东/华北/华南/西南，直营+联营） ----------
INSERT INTO store (store_code, store_name, region, nature, status, open_date) VALUES
  ('SST01', '上海徐汇店',   '华东', '直营', '营业中', DATE '2023-05-18'),
  ('SST02', '上海浦东店',   '华东', '直营', '营业中', DATE '2023-09-02'),
  ('SST03', '北京国贸店',   '华北', '直营', '营业中', DATE '2024-03-15'),
  ('SST04', '广州天河店',   '华南', '联营', '营业中', DATE '2024-06-20'),
  ('SST05', '成都春熙店',   '西南', '联营', '营业中', DATE '2024-11-08'),
  ('SST06', '杭州西湖店',   '华东', '直营', '筹建中', DATE '2026-09-01');

-- ---------- 组织树（集团 → 区域 → 门店） ----------
INSERT INTO org_unit (org_code, org_name, org_type, parent_code, store_code, region, sort_no) VALUES
  ('G001',   '美颜集团',   '集团', NULL,     NULL,    NULL, 0),
  ('R-HD',   '华东大区',   '区域', 'G001',   NULL,    '华东', 1),
  ('R-HB',   '华北大区',   '区域', 'G001',   NULL,    '华北', 2),
  ('R-HN',   '华南大区',   '区域', 'G001',   NULL,    '华南', 3),
  ('R-XN',   '西南大区',   '区域', 'G001',   NULL,    '西南', 4),
  ('O-SST01','上海徐汇店', '门店', 'R-HD',   'SST01', '华东', 1),
  ('O-SST02','上海浦东店', '门店', 'R-HD',   'SST02', '华东', 2),
  ('O-SST03','北京国贸店', '门店', 'R-HB',   'SST03', '华北', 1),
  ('O-SST04','广州天河店', '门店', 'R-HN',   'SST04', '华南', 1),
  ('O-SST05','成都春熙店', '门店', 'R-XN',   'SST05', '西南', 1),
  ('O-SST06','杭州西湖店', '门店', 'R-HD',   'SST06', '华东', 3);

-- ---------- 角色定义（staff.role_code 外键依赖；data_scope: 门店/区域/集团） ----------
INSERT INTO role_def (role_code, role_name, data_scope, role_sequence, medical, description) VALUES
  ('ROLE_GROUP_ADMIN',   '集团管理员', '集团', '01', false, '集团级全量数据与配置权限'),
  ('ROLE_AREA_MANAGER',  '区域经理',   '区域', '10', false, '区域内多门店数据查看'),
  ('ROLE_STORE_MANAGER', '店长',       '门店', '20', false, '门店管理与双签终审'),
  ('ROLE_CONSULTANT',    '咨询师',     '门店', '30', false, '客户归属、面诊与方案'),
  ('ROLE_DOCTOR',        '医生',       '门店', '40', true,  '执业医师，治疗与病历'),
  ('ROLE_THERAPIST',     '治疗师',     '门店', '50', false, '项目执行与到店服务'),
  ('ROLE_FRONT',         '前台',       '门店', '60', false, '接待登记与预约'),
  ('ROLE_CASHIER',       '收银',       '门店', '70', false, '收款与现金交接双签');

-- ---------- 员工（每店 4 人：店长/咨询师/医生/治疗师；咨询师为客户归属 owner） ----------
INSERT INTO staff (staff_id, staff_name, role_code, store_code, medical_licensed, status) VALUES
  ('SE001','许店长','ROLE_STORE_MANAGER','SST01',false,'在职'),
  ('SE002','林咨询','ROLE_CONSULTANT','SST01',false,'在职'),
  ('SE003','江医生','ROLE_DOCTOR','SST01',true,'在职'),
  ('SE004','苏治疗','ROLE_THERAPIST','SST01',false,'在职'),
  ('SE005','韩店长','ROLE_STORE_MANAGER','SST02',false,'在职'),
  ('SE006','沈咨询','ROLE_CONSULTANT','SST02',false,'在职'),
  ('SE007','古医生','ROLE_DOCTOR','SST02',true,'在职'),
  ('SE008','尤治疗','ROLE_THERAPIST','SST02',false,'在职'),
  ('SE009','程店长','ROLE_STORE_MANAGER','SST03',false,'在职'),
  ('SE010','曹咨询','ROLE_CONSULTANT','SST03',false,'在职'),
  ('SE011','严医生','ROLE_DOCTOR','SST03',true,'在职'),
  ('SE012','华治疗','ROLE_THERAPIST','SST03',false,'在职'),
  ('SE013','金店长','ROLE_STORE_MANAGER','SST04',false,'在职'),
  ('SE014','魏咨询','ROLE_CONSULTANT','SST04',false,'在职'),
  ('SE015','陶医生','ROLE_DOCTOR','SST04',true,'在职'),
  ('SE016','姜治疗','ROLE_THERAPIST','SST04',false,'在职'),
  ('SE017','戚店长','ROLE_STORE_MANAGER','SST05',false,'在职'),
  ('SE018','谢咨询','ROLE_CONSULTANT','SST05',false,'在职'),
  ('SE019','邹医生','ROLE_DOCTOR','SST05',true,'在职'),
  ('SE020','喻治疗','ROLE_THERAPIST','SST05',false,'在职'),
  ('SE021','柏店长','ROLE_STORE_MANAGER','SST06',false,'在职'),
  ('SE022','水咨询','ROLE_CONSULTANT','SST06',false,'在职'),
  ('SE023','窦医生','ROLE_DOCTOR','SST06',true,'在职'),
  ('SE024','章治疗','ROLE_THERAPIST','SST06',false,'在职');

-- ---------- M7 数据域：员工大区回填（门店员工按所属门店派生；幂等） ----------
ALTER TABLE staff ADD COLUMN IF NOT EXISTS region varchar(16);
UPDATE staff s SET region = st.region
FROM store st
WHERE s.store_code IS NOT NULL AND s.store_code <> ''
  AND st.store_code = s.store_code
  AND (s.region IS NULL OR s.region = '');

-- ---------- 客户标签（tag_name 全局唯一，避开现有 高客单/敏感肌/价格敏感 等） ----------
INSERT INTO customer_tag (tag_id, tag_name, category) VALUES
  ('STG01','高净值客户','价值'),
  ('STG02','高频到店','行为'),
  ('STG03','沉睡唤回','行为'),
  ('STG04','流失风险','价值'),
  ('STG05','新客培育','价值'),
  ('STG06','抗衰偏好','消费'),
  ('STG07','祛痘需求','消费'),
  ('STG08','敏感修复','肤质'),
  ('STG09','油性肌肤','肤质'),
  ('STG10','转介绍达人','行为'),
  ('STG11','术后随访','医疗'),
  ('STG12','促销敏感','消费');
