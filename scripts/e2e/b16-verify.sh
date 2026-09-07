#!/usr/bin/env bash
# B16 售卡链路 API 验证（seed 栈，网关 18443）
set -u
GW="https://127.0.0.1:18443"
TOKEN=$(curl -sk -X POST "$GW/api/org/auth/login" -H "Content-Type: application/json" \
  -d '{"loginName":"SE001","password":"meiyun123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && [ "${TOKEN:0:10}" = "eyJhbGciOi" ] || { echo "登录失败"; exit 1; }
echo "== 0. 登录成功 SE001（SST01 店长）"

AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

echo ""
echo "== 1. 在售卡模板（catalog，SST01 ON_SHELF）"
curl -sk "${AUTH[@]}" "$GW/api/stores/catalog?storeCode=SST01&status=ON_SHELF" | python3 -c "
import json,sys
d=json.load(sys.stdin)
data=d.get('data',d)
items=data.get('content',data) if isinstance(data,dict) else data
if isinstance(items,dict): items=items.get('content',[])
for p in items:
    print(f\"  {p.get('productCode')} {p.get('name')} type={p.get('productType')} price={p.get('priceYuan')}元 sessions={p.get('sessions')} validity={p.get('validityDays')}天 status={p.get('status')}\")
"

echo ""
echo "== 2. internal 取产品定价端点（store-service）"
curl -sk "${AUTH[@]}" "$GW/api/stores/internal/catalog/CD-001" | head -c 400; echo

echo ""
echo "== 3. 售卡下单 POST /api/txn/card-order（CD-001，客户 SC001）"
ORDER_RESP=$(curl -sk -X POST "${AUTH[@]}" "$GW/api/txn/card-order" \
  -d '{"customerId":"SC001","storeCode":"SST01","consultant":"SE001","productCode":"CD-001","operator":"SE001"}')
echo "$ORDER_RESP" | head -c 800; echo
ORDER_NO=$(echo "$ORDER_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('orderNo',''))")
ORDER_AMT=$(echo "$ORDER_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('amount',''))")
echo "  -> orderNo=$ORDER_NO amount=$ORDER_AMT 分"

echo ""
echo "== 4. 售卡单用 balance 支付应被拒（中文 400）"
curl -sk -X POST "${AUTH[@]}" "$GW/api/txn/order/$ORDER_NO/pay" \
  -d '{"method":"balance","tendered":'$ORDER_AMT',"operator":"SE001"}' | head -c 400; echo

echo ""
echo "== 5. 微信支付收齐 POST /api/txn/order/$ORDER_NO/pay"
curl -sk -X POST "${AUTH[@]}" "$GW/api/txn/order/$ORDER_NO/pay" \
  -d '{"method":"wxpay","tendered":'$ORDER_AMT',"operator":"SE001"}' | head -c 800; echo

echo ""
echo "== 6. 客户 SC001 卡列表（应有新开卡：cardType/saleNo/productCode/expiresAt）"
curl -sk "${AUTH[@]}" "$GW/api/customer/customers/SC001/cards" | python3 -c "
import json,sys
d=json.load(sys.stdin)
cards=d.get('data',[])
if isinstance(cards,dict): cards=cards.get('content',[])
for c in cards:
    print(f\"  {c.get('cardNo')} {c.get('cardItem')} type={c.get('cardType')} balance={c.get('balance')} times={c.get('remainTimes')}/{c.get('totalTimes')} gift={c.get('giftBalance')} sale={c.get('saleNo')} product={c.get('productCode')} exp={c.get('expiresAt')} status={c.get('status')}\")
" 2>/dev/null || curl -sk "${AUTH[@]}" "$GW/api/customer/customers/SC001/cards" | head -c 800

echo ""
echo "== 7. 售卡订单已落 biz_kind/快照"
docker exec meiyun-pg psql -U meiyun -d meiyun_seed -t -c "SELECT order_no, biz_kind, product_code, card_type, card_total_times, card_validity_days, status, amount FROM txn_order WHERE order_no='$ORDER_NO';"

echo "== 8. 新开卡首笔流水（RECHARGE, biz_ref=$ORDER_NO）"
NEW_CARD=$(docker exec meiyun-pg psql -U meiyun -d meiyun_seed -t -A -c "SELECT card_no FROM member_card WHERE sale_no='$ORDER_NO' LIMIT 1;")
echo "  新卡卡号: $NEW_CARD"
docker exec meiyun-pg psql -U meiyun -d meiyun_seed -c "SELECT ledger_id, card_no, change_type, amount, balance_after, biz_ref, operator FROM card_ledger WHERE card_no='$NEW_CARD' ORDER BY ledger_id;"

echo ""
echo "== 9. 预收分录（finance RF-DEPOSIT/IN）"
docker exec meiyun-pg psql -U meiyun -d meiyun_seed -c "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'fin%';"
