#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
美研云 meiyun_seed 独立测试库 · 客户富画像种子生成器（确定性、可复现）。
产出 backend/db/seed/02_customer_full.sql。

契约（与真实库一致，见 meiyun-dev-rules）：
  - 枚举中文；channel 存英文 key（前端字典转中文）。
  - 金额 bigint 存「分」；customer.total_spend decimal 存「元」= 已收款订单金额/100。
  - 逻辑外键无物理约束，ID 由本生成器保证一致。
  - ID 前缀：客户 SC、订单 OD、卡 MC、预约 AP、面诊 CO（与现有数据隔离）。
"""
import random
import os
import io
from datetime import date, timedelta
from collections import defaultdict

rng = random.Random(20260831)  # 固定种子 → 可复现
TODAY = date(2026, 8, 31)

OUT = []
def w(line=""):
    OUT.append(line)

def q(s):
    return "'" + str(s).replace("'", "''") + "'"

def d_str(d):
    return d.strftime("%Y-%m-%d")

def ts_str(d):
    return d.strftime("%Y-%m-%d %H:%M:%S+08:00")

STORES = ["SST01", "SST02", "SST03", "SST04", "SST05"]  # SST06 筹建中不挂客户
CONSULTANT = {"SST01": "SE002", "SST02": "SE006", "SST03": "SE010", "SST04": "SE014", "SST05": "SE018"}
DOCTOR = {"SST01": "SE003", "SST02": "SE007", "SST03": "SE011", "SST04": "SE015", "SST05": "SE019"}

PROJECTS = [
    ("水光针单次", 88000), ("超声炮局部", 158000), ("热玛吉全面部", 298000),
    ("光子嫩肤", 128000), ("激光祛痘", 68000), ("敏感肌修复单次", 66000),
    ("玻尿酸填充", 268000), ("肉毒素除皱", 98000), ("皮秒祛斑", 198000),
    ("小气泡清洁", 19900), ("果酸焕肤", 39900), ("射频紧致", 158000),
]
PREMIUM = [p for p in PROJECTS if p[1] >= 128000]
CARDS = [
    ("年卡·面部抗衰10次", 10, 680000), ("季卡·水光针3次", 3, 240000),
    ("热玛吉4次卡", 4, 980000), ("敏感肌修复8次卡", 8, 480000),
    ("祛痘6次卡", 6, 360000), ("光子嫩肤5次卡", 5, 580000),
]
CHANNELS = ["WALK_IN", "REFERRAL", "XIAOHONGSHU", "WECHAT", "DOUYIN", "MEITUAN", "OTHER"]
SURNAMES = list("王李张刘陈杨黄赵周吴徐孙马朱胡郭何林高罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
GIVEN_F = ["雨桐", "欣怡", "梓涵", "诗琪", "佳琪", "思远", "雅静", "若曦", "晓彤", "梦洁", "慧敏", "丽娟", "桂英", "秀兰", "玉兰", "婷婷", "雪梅", "丹妮", "安妮", "娜娜"]
GIVEN_M = ["志强", "建国", "伟杰", "浩然", "子轩", "俊杰", "明辉", "国华", "磊", "洋"]

# RFM 画像分段（共 100）：(level,status,n,订单数,客单价分档,最近消费距今天,目标积分,基础标签)
SEGMENTS = [
    ("黑卡", "活跃", 5,  (12, 22), (158000, 298000), (5, 40),   (80000, 220000), ["STG01", "STG02", "STG06"]),
    ("钻石", "活跃", 12, (8, 15),  (98000, 268000),  (8, 55),   (30000, 90000),  ["STG01", "STG02", "STG06"]),
    ("金卡", "活跃", 23, (5, 10),  (66000, 198000),  (10, 70),  (8000, 32000),   ["STG02", "STG06"]),
    ("银卡", "活跃", 30, (2, 6),   (39900, 128000),  (15, 90),  (1500, 9000),    ["STG05"]),
    ("普通", "活跃", 8,  (1, 3),   (19900, 88000),   (20, 100), (200, 2000),     ["STG05"]),
    ("普通", "沉睡", 8,  (1, 3),   (19900, 68000),   (120, 200),(0, 1500),       ["STG03"]),
    ("银卡", "沉睡", 6,  (3, 7),   (39900, 128000),  (130, 220),(1000, 6000),    ["STG03", "STG04"]),
    ("金卡", "流失", 4,  (5, 9),   (66000, 158000),  (260, 400),(3000, 15000),   ["STG04"]),
    ("普通", "流失", 4,  (1, 2),   (19900, 66000),   (280, 450),(0, 800),        ["STG04"]),
]
SKIN_TAGS = ["STG07", "STG08", "STG09"]

cust_rows, tag_rows, order_rows, card_rows = [], [], [], []
item_rows = []  # 订单收费子项 order_item
appt_rows, consult_rows, push_rows = [], [], []
ledger_raw = defaultdict(list)
oid = cid = apid = coid = caid = 0

for seg in SEGMENTS:
    level, status, n, (o_lo, o_hi), (_, _), (g_lo, g_hi), (pt_lo, pt_hi), base_tags = seg
    for _ in range(n):
        cid += 1
        cust_id = "SC%03d" % cid
        store = rng.choice(STORES)
        male = rng.random() < 0.12
        gender = "男" if male else "女"
        name = rng.choice(SURNAMES) + (rng.choice(GIVEN_M) if male else rng.choice(GIVEN_F))
        phone = "139%08d" % (20260000 + cid)
        birth = date(1970 + rng.randint(0, 32), rng.randint(1, 12), rng.randint(1, 28))
        channel = rng.choice(CHANNELS)
        owner = CONSULTANT[store]
        target_pts = rng.randint(pt_lo, pt_hi)
        n_orders = rng.randint(o_lo, o_hi)
        last_gap = rng.randint(g_lo, g_hi)

        last_d = TODAY - timedelta(days=last_gap)
        order_dates = sorted({last_d - timedelta(days=rng.randint(0, 240)) for _ in range(n_orders)})
        order_dates = order_dates[-n_orders:]

        paid_cents = 0
        n_paid = 0
        card_date = None
        for od in order_dates:
            oid += 1
            order_no = "OD%s-%06d" % (od.strftime("%Y%m%d"), oid)
            # 收费子项：一笔订单含 1~3 个项目（黑卡/钻石更可能多项目联单）。
            # 订单主表 project 取首个项目名作概要；订单总额 = 各子项小计之和（单位:分）。
            n_items = rng.choices([1, 2, 3], weights=[45, 40, 15])[0]
            if level in ("黑卡", "钻石"):
                n_items = rng.choices([1, 2, 3], weights=[25, 45, 30])[0]
            chosen = []
            used = set()
            for _li in range(n_items):
                proj, price = rng.choice(PREMIUM) if (level in ("黑卡", "钻石") and rng.random() < 0.5) else rng.choice(PROJECTS)
                # 同单内项目去重（重复则换名）
                guard = 0
                while proj in used and guard < 6:
                    proj, price = rng.choice(PROJECTS)
                    guard += 1
                used.add(proj)
                qty = rng.choices([1, 1, 1, 2], weights=[60, 20, 10, 10])[0]
                unit = int(price * rng.uniform(0.9, 1.15) / 100) * 100
                line_amt = unit * qty
                chosen.append((proj, qty, unit, line_amt))
            amount = sum(c[3] for c in chosen)
            main_proj = chosen[0][0]
            for li, (proj, qty, unit, line_amt) in enumerate(chosen, start=1):
                item_rows.append("(%s,%d,%s,%d,%d,%d,%s)" % (
                    q(order_no), li, q(proj), qty, unit, line_amt, q(ts_str(od))))
            r = rng.random()
            if status in ("沉睡", "流失"):
                ostatus = "已收款" if r < 0.9 else "已取消"
            else:
                ostatus = "已收款" if r < 0.82 else ("待签核" if r < 0.92 else ("待收款" if r < 0.97 else "已取消"))
            contra = "GREEN" if rng.random() < 0.9 else "YELLOW"
            cons = DOCTOR[store] if rng.random() < 0.7 else CONSULTANT[store]
            if ostatus == "已收款" and rng.random() < 0.8:
                s1, s2 = q(DOCTOR[store]), q(CONSULTANT[store])
            else:
                s1, s2 = "NULL", "NULL"
            order_rows.append(
                "(%s,%s,%s,%s,%d,%s,%s,NULL,NULL,NULL,%s,%s,%s,%s)" % (
                    q(order_no), q(cust_id), q(store), q(main_proj), amount, q(cons), q(contra),
                    s1, s2, q(ostatus), q(ts_str(od))))
            if ostatus == "已收款":
                paid_cents += amount
                n_paid += 1

        total_spend = round(paid_cents / 100.0, 2)
        visit_count = max(n_paid, rng.randint(0, 2))
        created = order_dates[0] - timedelta(days=rng.randint(10, 60)) if order_dates else TODAY - timedelta(days=300)

        cust_rows.append(
            "(%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%.2f,%d)" % (
                q(cust_id), q(name), q(phone), q(gender), q(d_str(birth)), q(level), q(store),
                target_pts, q(status), q(ts_str(created)), q(channel), q(owner),
                total_spend, visit_count))

        tags = list(base_tags)
        if rng.random() < 0.45:
            tags.append(rng.choice(SKIN_TAGS))
        if channel == "REFERRAL" and rng.random() < 0.6:
            tags.append("STG10")
        if status == "活跃" and n_paid >= 6 and rng.random() < 0.4:
            tags.append("STG11")
        if rng.random() < 0.3:
            tags.append("STG12")
        for t in dict.fromkeys(tags):
            tag_rows.append("(%s,%s)" % (q(cust_id), q(t)))

        # 会员卡：按等级开 1-N 张项目卡（一客可持多卡、卡项目不重复），保证卡数充足
        if level == "黑卡":
            n_cards = rng.randint(2, 3)
        elif level in ("钻石", "金卡"):
            n_cards = rng.randint(1, 2)
        elif level == "银卡":
            n_cards = 1 if rng.random() < 0.95 else 0
        else:
            n_cards = 1 if rng.random() < 0.35 else 0
        has_card = n_cards > 0
        card_dates = []
        used_card_items = set()
        for ci in range(n_cards):
            cname, ctimes, cprice = rng.choice(CARDS)
            _guard = 0
            while cname in used_card_items and _guard < 10:
                cname, ctimes, cprice = rng.choice(CARDS); _guard += 1
            used_card_items.add(cname)
            remain = rng.randint(0, ctimes) if status != "流失" else 0
            if status == "流失":
                cstatus = rng.choice(["已退卡", "已用完"])
            elif remain == 0:
                cstatus = "已用完"
            else:
                cstatus = "在用"
            card_balance = int(cprice * remain / ctimes / 100) * 100 if cstatus == "在用" else 0
            card_date = created + timedelta(days=rng.randint(5, 40) + ci * 30)
            caid += 1
            card_dates.append(card_date)
            card_rows.append("(%s,%s,%s,%s,%d,%d,%d,%s,%s)" % (
                q("MC%s-%06d" % (card_date.strftime("%Y%m%d"), caid)), q(cust_id), q(cname), q(store),
                ctimes, remain, card_balance, q(cstatus), q(ts_str(card_date))))
        first_card_date = card_dates[0] if card_dates else None

        # 积分流水：期末余额 == target_pts（开卡赠积分仅首张卡触发一次，避免重复）
        led = []
        bal = 0
        if has_card and first_card_date is not None:
            grant = min(target_pts, rng.choice([2000, 3000, 5000, 8000]))
            led.append((first_card_date, "开卡赠积分", grant)); bal += grant
        redeem = 0
        if target_pts >= 8000 and rng.random() < 0.5:
            redeem = min(rng.choice([2000, 3000, 5000]), bal + max(0, target_pts - bal))
        spend_earn = target_pts + redeem - bal
        if spend_earn > 0:
            led.append((created + timedelta(days=120), "消费累积", spend_earn)); bal += spend_earn
        elif spend_earn < 0:
            redeem = 0; bal = 0
            led = [(created + timedelta(days=120), "消费累积", target_pts)]; bal = target_pts
        if redeem > 0 and bal >= redeem:
            led.append((created + timedelta(days=180), "积分兑换·水光针单次", -redeem)); bal -= redeem
        if bal != target_pts:
            led.append((created + timedelta(days=200), "消费累积", target_pts - bal)); bal = target_pts
        for dt, reason, amt in led:
            if amt != 0:
                ledger_raw[cust_id].append((dt, reason, amt))

        # 预约
        if status == "活跃":
            for k in range(rng.randint(1, 3)):
                apid += 1
                ad = TODAY + timedelta(days=rng.randint(-25, 14))
                if ad > TODAY:
                    astatus, arrived = "已预约", "NULL"
                else:
                    astatus = rng.choice(["已到店", "已到店", "已到店", "未到诊"])
                    arrived = q(ts_str(ad)) if astatus == "已到店" else "NULL"
                appt_rows.append("(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
                    q("AP%s-%06d" % (ad.strftime("%Y%m%d"), apid)), q(cust_id), q(store),
                    q(rng.choice(PROJECTS)[0]), q(d_str(ad)), q("%02d:00" % rng.randint(9, 18)),
                    q(DOCTOR[store]), q(rng.choice(["B端登记", "C端小程序", "C端App"])), q(astatus), arrived))
        elif status == "沉睡" and rng.random() < 0.4:
            apid += 1
            ad = last_d + timedelta(days=rng.randint(20, 60))
            appt_rows.append("(%s,%s,%s,%s,%s,%s,%s,%s,%s,NULL)" % (
                q("AP%s-%06d" % (ad.strftime("%Y%m%d"), apid)), q(cust_id), q(store),
                q(rng.choice(PROJECTS)[0]), q(d_str(ad)), q("14:00"), q(DOCTOR[store]),
                q("C端小程序"), q("未到诊")))

        # 面诊：活跃客户 1-3 次到店面诊，沉睡/流失提高建档概率
        if status == "活跃":
            n_consult = rng.randint(1, 3) if rng.random() < 0.9 else 0
        else:
            n_consult = 1 if rng.random() < 0.6 else 0
        for _ in range(n_consult):
            coid += 1
            cd = created + timedelta(days=rng.randint(3, 90))
            skins = ["偏油，毛孔粗大", "敏感泛红，屏障受损", "干燥缺水，细纹", "色斑暗沉", "痘痘肌，炎症期"]
            needs = ["希望改善面部松弛", "换季敏感想修复屏障", "想做祛痘控油", "关注抗衰紧致", "想提亮肤色"]
            consult_rows.append("(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
                q("CO%s-%06d" % (cd.strftime("%Y%m%d"), coid)), q(cust_id), q(store),
                q("青霉素过敏" if rng.random() < 0.2 else "无"),
                q("利多卡因轻微反应" if rng.random() < 0.1 else "无"),
                q("否"), q("否"), q("否"),
                q(rng.choice(skins)), q(rng.choice(needs)), q(CONSULTANT[store])))

        # 触达：沉睡/流失多条唤醒触达，活跃客户少量活动/关怀触达
        if status in ("沉睡", "流失"):
            n_push = rng.randint(2, 4)
        elif rng.random() < 0.45:
            n_push = rng.randint(1, 2)
        else:
            n_push = 0
        for pi in range(n_push):
            pd = TODAY - timedelta(days=rng.randint(5, 60) + pi * 7)
            if status == "流失":
                content = rng.choice(["好久不见，您的专属顾问为您准备了回归礼", "流失召回：老客专属抗衰套餐限时回归", "您的积分即将到期，到店核销享好礼"])
            elif status == "沉睡":
                content = rng.choice(["尊贵的会员，限时抗衰套餐回归，预约到店享专属折扣", "换季护肤提醒，您的专属顾问期待您的到店", "沉睡唤醒：本月到店赠皮肤检测一次"])
            else:
                content = rng.choice(["生日月专属福利到店领取", "新品热玛吉体验官招募中", "会员日双倍积分活动开启"])
            push_rows.append("(%s,%s,%s,%s)" % (
                q(cust_id), q(rng.choice(["短信", "小程序", "App"])), q(content), q(ts_str(pd))))

# 积分流水按客户+时间排序，重算 balance_after
ledger_sql = []
for cust in sorted(ledger_raw.keys()):
    bal = 0
    for dt, reason, amt in sorted(ledger_raw[cust], key=lambda x: x[0]):
        bal = max(0, bal + amt)
        ledger_sql.append("(%s,%d,%d,%s,%s)" % (q(cust), amt, bal, q(reason), q(ts_str(dt))))

w("-- 02 客户富画像种子（由 scripts/gen_seed.py 确定性生成，勿手改）")
w("-- 100 客户：黑卡5/钻石12/金卡27(含流失4)/银卡36(含沉睡6)/普通20(含沉睡8流失4)")
w()
w("INSERT INTO customer (customer_id,name,phone,gender,birth_date,level,store_code,points,status,created_at,channel,owner_staff_id,total_spend,visit_count) VALUES")
w(",\n".join(cust_rows) + ";")
w()
w("INSERT INTO customer_tag_rel (customer_id,tag_id) VALUES")
w(",\n".join(tag_rows) + ";")
w()
w("INSERT INTO txn_order (order_no,customer_id,store_code,project,amount,consultant,contra_check,contra_detail,exemption_sign1,exemption_sign2,sign1,sign2,status,created_at) VALUES")
w(",\n".join(order_rows) + ";")
w()
w("INSERT INTO order_item (order_no,line_no,item_name,qty,unit_price,amount,created_at) VALUES")
w(",\n".join(item_rows) + ";")
w()
w("INSERT INTO member_card (card_no,customer_id,card_item,store_code,total_times,remain_times,balance,status,created_at) VALUES")
w(",\n".join(card_rows) + ";")
w()
w("INSERT INTO points_ledger (customer_id,change_amt,balance_after,reason,created_at) VALUES")
w(",\n".join(ledger_sql) + ";")
w()
w("INSERT INTO appointment (appt_no,customer_id,store_code,project,appt_date,appt_time,doctor,source,status,arrived_at) VALUES")
w(",\n".join(appt_rows) + ";")
w()
w("INSERT INTO consultation (consult_id,customer_id,store_code,allergy_history,drug_allergy,scar_constitution,pregnancy,coagulation_abn,skin_status,needs,consultant) VALUES")
w(",\n".join(consult_rows) + ";")
w()
w("INSERT INTO push_record (customer_id,push_type,content,sent_at) VALUES")
w(",\n".join(push_rows) + ";")
w()

out_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend", "db", "seed", "02_customer_full.sql"))
with io.open(out_path, "w", encoding="utf-8") as f:
    f.write("\n".join(OUT))

print("customers=%d tagRels=%d orders=%d orderItems=%d cards=%d ledger=%d appts=%d consults=%d pushes=%d" % (
    len(cust_rows), len(tag_rows), len(order_rows), len(item_rows), len(card_rows), len(ledger_sql),
    len(appt_rows), len(consult_rows), len(push_rows)))
print("written:", out_path)
