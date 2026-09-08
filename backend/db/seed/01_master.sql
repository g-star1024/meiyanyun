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
-- B23：point_rule 补全签到/生日倍乘/转介绍/手动调分开关四列（rule_id 恒 1）。
INSERT INTO point_rule
  (rule_id, earn_rate, redeem_ratio, expire_months, sign_in_reward, birthday_multiplier, referral_reward, manual_grant_enabled, updated_at)
VALUES
  (1, 1.00, 100.00, 12, 10, 2.00, 500, true, '2026-08-15 00:00:00+08');
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

-- ---------- B23 积分池聚合（单行 pool_id=1，对齐 prepay_pool 口径；积分单位为分） ----------
INSERT INTO points_pool (pool_id, total_issued, gained_month, redeemed_month, expiring_90d) VALUES
  (1, 8640000, 628500, 186400, 452300);

-- ---------- B23 积分商城商品（M3-20；商品类型中文枚举 项目/实物/优惠券/服务；
--   库存 -1=不限（优惠券）；状态 已上架/已下架（低库存≤50 为前端派生不入库，故术后套装/热玛吉库存小仍存已上架）；
--   单号 MP+yyyyMMdd-6 位序号，与后端 nextProductNo 同口径。对齐前端 mock 活规格 7 商品。） ----------
INSERT INTO mall_product
  (product_id, product_name, product_type, points_price, stock, status, cover, description, redeemed_count, created_at)
VALUES
  ('MP20260801-000001','水光体验次卡','项目',2000,156,'已上架','项目',
   '凭兑换记录到店核销 1 次水光基础护理，含面诊与术后敷膜；有效期 90 天，需提前预约。',89,'2026-08-01 10:00:00+08'),
  ('MP20260801-000002','医用面膜 1 片装','实物',800,320,'已上架','实物',
   '院线同款医用冷敷贴，单片装；兑后 3 个工作日内按收货地址寄出，运费由门店承担。',215,'2026-08-01 10:05:00+08'),
  ('MP20260803-000003','术后护理套装','实物',5800,42,'已上架','实物',
   '含医用面膜 5 片、修复精华与防晒小样，适合光电项目后 7 天修护期使用；兑后寄出。',23,'2026-08-03 14:20:00+08'),
  ('MP20260805-000004','清透防晒乳 SPF50+','实物',1500,0,'已下架','实物',
   '50ml 院线装清透防晒乳；本批次已兑罄，补货后重新上架。',178,'2026-08-05 09:30:00+08'),
  ('MP20260806-000005','满 500 减 100 优惠券','优惠券',3000,-1,'已上架','券',
   '兑换后发放至会员卡包，到店消费满 500 元可用，每单限用 1 张，有效期 60 天。',45,'2026-08-06 11:00:00+08'),
  ('MP20260808-000006','VIP 专属皮肤检测 1 次','服务',1200,80,'已上架','服务',
   'AI 皮肤检测 1 次，含毛孔/色斑/敏感三维报告与咨询师解读；需提前预约，有效期 60 天。',32,'2026-08-08 15:40:00+08'),
  ('MP20260810-000007','热玛吉体验券','项目',12000,20,'已上架','项目',
   '热玛吉面部体验 1 次（含下颌缘），由执业医师操作；兑换后咨询师 24 小时内联系排期。',8,'2026-08-10 16:00:00+08');

-- ---------- B23 积分商城兑换单（C 端下单 → B 端双签审核队列；
--   单号 EX+yyyyMMdd-6 位；状态 待审核/已通过/已拒绝/已发放；points_spent=单价×qty；
--   实物类填 ship_name/ship_phone/ship_address（电话脱敏存储）；已通过/已发放/已拒绝 落双签；
--   种子为历史演示单，客户积分余额不联动扣减（与真实链路一致：审核通过才扣分）。对齐 mock 活规格。） ----------
INSERT INTO mall_exchange
  (exchange_id, product_id, customer_id, points_spent, qty, status,
   sign1, sign1_role, signed_at1, sign2, sign2_role, signed_at2, reject_reason,
   ship_name, ship_phone, ship_address, client_token, fulfilled_at, created_at)
VALUES
  ('EX20260828-000031','MP20260801-000001','SC001',2000,1,'待审核',
   NULL,NULL,NULL,NULL,NULL,NULL,NULL,
   '方梦洁','139****0001','上海市静安区南京西路 1266 号','seed-ex-20260828-31',NULL,'2026-08-28 10:12:00+08'),
  ('EX20260828-000032','MP20260801-000002','SC002',1600,2,'待审核',
   NULL,NULL,NULL,NULL,NULL,NULL,NULL,
   '顾明辉','139****0002','上海市浦东新区世纪大道 88 号','seed-ex-20260828-32',NULL,'2026-08-28 11:05:00+08'),
  ('EX20260827-000028','MP20260806-000005','SC026',3000,1,'待审核',
   NULL,NULL,NULL,NULL,NULL,NULL,NULL,
   NULL,NULL,NULL,'seed-ex-20260827-28',NULL,'2026-08-27 16:40:00+08'),
  ('EX20260827-000025','MP20260808-000006','SC033',1200,1,'待审核',
   NULL,NULL,NULL,NULL,NULL,NULL,NULL,
   NULL,NULL,NULL,'seed-ex-20260827-25',NULL,'2026-08-27 15:02:00+08'),
  ('EX20260826-000019','MP20260803-000003','SC018',5800,1,'待审核',
   NULL,NULL,NULL,NULL,NULL,NULL,NULL,
   '任雪梅','139****0018','上海市朝阳区国贸建外 SOHO 3 号楼','seed-ex-20260826-19',NULL,'2026-08-26 09:48:00+08'),
  ('EX20260825-000014','MP20260801-000001','SC041',2000,1,'已通过',
   '韩店长','店长','2026-08-25 14:00:00+08','卫运营','运营','2026-08-25 14:20:00+08',NULL,
   NULL,NULL,NULL,'seed-ex-20260825-14',NULL,'2026-08-25 10:30:00+08'),
  ('EX20260824-000009','MP20260810-000007','SC055',12000,1,'已发放',
   '韩店长','店长','2026-08-24 10:00:00+08','卫运营','运营','2026-08-24 10:30:00+08',NULL,
   NULL,NULL,NULL,'seed-ex-20260824-09','2026-08-25 16:00:00+08','2026-08-24 09:10:00+08'),
  ('EX20260822-000022','MP20260805-000004','SC060',1500,1,'已拒绝',
   '许店长','店长','2026-08-22 15:00:00+08','曹运营','运营','2026-08-22 15:30:00+08','收货地址无法送达',
   '丁梦洁','139****0060','上海市崇明区偏远村组（快递超区）','seed-ex-20260822-22',NULL,'2026-08-22 11:20:00+08');
