#!/bin/bash
# B13 写链路 E2E（seed 栈 https://localhost:18443）
# 覆盖：建房/409幂等/400校验/床位设维护/重复维护409/恢复/重复恢复409
#       建设备/409/400/状态变更/登记记录(回写下次校准/状态恢复/折旧) + 日志 + audit_log 落库
set -uo pipefail
BASE="https://localhost:18443"
SC="SST01"
login(){ curl -sk -X POST "$BASE/api/org/auth/login" -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"$1\",\"password\":\"meiyun123\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])"; }
code(){ curl -sk -o /tmp/b13_body -w "%{http_code}" "$@"; }
show(){ echo "    -> HTTP $1 | $(cat /tmp/b13_body | head -c 300)"; }

STORE=$(login SE001)   # 许店长 STORE_MGR SST01，有 room:edit/equipment:edit
echo "=== W0 店长 token 就绪 ==="

echo "===== W1 建房 E01（2 床）应 200 ====="
c=$(code -X POST "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"roomCode\":\"E01\",\"name\":\"E2E测试治疗室\",\"roomType\":\"TREATMENT\",\"bedCount\":2}")
show "$c"
ROOM_ID=$(cat /tmp/b13_body | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "    roomId=$ROOM_ID"

echo "===== W2 重复建房 E01 应 409 ====="
c=$(code -X POST "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"roomCode\":\"E01\",\"name\":\"重复房\",\"roomType\":\"TREATMENT\",\"bedCount\":1}")
show "$c"

echo "===== W3 非法房类/床数 应 400 ====="
c=$(code -X POST "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"roomCode\":\"E02\",\"name\":\"非法房类\",\"roomType\":\"WARD\",\"bedCount\":1}")
show "$c"
c=$(code -X POST "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"roomCode\":\"E03\",\"name\":\"床数越界\",\"roomType\":\"TREATMENT\",\"bedCount\":99}")
show "$c"

echo "===== W4 取 E01 床位 id，设 E01-B1 维护 应 200 ====="
BED1=$(curl -sk "$BASE/api/stores/rooms?type=TREATMENT" -H "Authorization: Bearer $STORE" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
for r in d:
    if r['roomCode']=='E01':
        print([b['id'] for b in r['beds'] if b['bedCode']=='E01-B1'][0])")
echo "    bedId(E01-B1)=$BED1"
c=$(code -X POST "$BASE/api/stores/beds/$BED1/maintenance" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"reason\":\"E2E：床腿松动待紧固\"}")
show "$c"

echo "===== W5 无原因设维护 应 400；重复设维护 应 409 ====="
c=$(code -X POST "$BASE/api/stores/beds/$BED1/maintenance" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"reason\":\"\"}")
show "$c"
c=$(code -X POST "$BASE/api/stores/beds/$BED1/maintenance" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"reason\":\"再次维护\"}")
show "$c"

echo "===== W6 恢复 E01-B1 应 200；重复恢复 应 409 ====="
c=$(code -X POST "$BASE/api/stores/beds/$BED1/restore" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\"}")
show "$c"
c=$(code -X POST "$BASE/api/stores/beds/$BED1/restore" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\"}")
show "$c"

echo "===== W7 建设备 EQ-E2E01（¥120000=12000000分）应 200 ====="
c=$(code -X POST "$BASE/api/stores/equipments" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"assetNo\":\"EQ-E2E01\",\"name\":\"E2E测试激光仪\",\"brand\":\"测试厂\",\"model\":\"T-1\",\"category\":\"LASER\",\"location\":\"E01 E2E测试治疗室\",\"purchaseAmountFen\":12000000,\"lifespanYears\":6}")
show "$c"
EQ_ID=$(cat /tmp/b13_body | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "    eqId=$EQ_ID"

echo "===== W8 重复资产号 应 409；非法分类 应 400 ====="
c=$(code -X POST "$BASE/api/stores/equipments" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"assetNo\":\"EQ-E2E01\",\"name\":\"重复\",\"category\":\"LASER\",\"purchaseAmountFen\":100}")
show "$c"
c=$(code -X POST "$BASE/api/stores/equipments" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"assetNo\":\"EQ-E2E02\",\"name\":\"非法分类\",\"category\":\"MAGIC\",\"purchaseAmountFen\":100}")
show "$c"

echo "===== W9 状态置 CALIBRATING 应 200；非法状态 应 400 ====="
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/status" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"status\":\"CALIBRATING\",\"note\":\"E2E：送检校准\"}")
show "$c"
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/status" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"status\":\"FLYING\"}")
show "$c"

echo "===== W10 登记校准记录（nextAt=+180天，cost=5000元=500000分）应 200 ====="
NEXT=$(python3 -c "import datetime;print((datetime.date.today()+datetime.timedelta(days=180)).isoformat())")
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/records" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"type\":\"CALIBRATION\",\"summary\":\"E2E：年度能量校准通过\",\"vendor\":\"测试计量院\",\"at\":\"$(date +%F)\",\"nextAt\":\"$NEXT\",\"costFen\":500000}")
show "$c"

echo "===== W11 登记维修记录（非法类型应 400；REPAIR 类型 + cost 3000元 应 200） ====="
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/records" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"type\":\"UPGRADE\",\"summary\":\"非法类型\",\"costFen\":1}")
show "$c"
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/records" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"type\":\"REPAIR\",\"summary\":\"E2E：更换手柄线缆\",\"vendor\":\"测试售后\",\"at\":\"$(date +%F)\",\"costFen\":300000}")
show "$c"

echo "===== W12 空 summary 应 400 ====="
c=$(code -X POST "$BASE/api/stores/equipments/$EQ_ID/records" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"$SC\",\"type\":\"MAINTENANCE\",\"summary\":\"\",\"costFen\":1}")
show "$c"

echo "===== W13 回写核验：设备状态应 NORMAL（校准后恢复）、折旧=8000元、下次校准=$NEXT、记录 2 条 ====="
curl -sk "$BASE/api/stores/equipments/$EQ_ID?storeCode=$SC" -H "Authorization: Bearer $STORE" \
  | python3 -c "
import sys,json
e=json.load(sys.stdin)
print('  status =',e['status'],'(期望 NORMAL)')
print('  depreciated =',e['depreciated'],'(期望 8000.0)')
print('  nextCalibrationAt =',e['nextCalibrationAt'],'(期望 $NEXT)')
print('  records =',len(e['records']),'(期望 2)')
for r in e['records']: print('   ',r['type'],r['at'],r['summary'],'cost=',r['cost'])"

echo "===== W14 房间操作日志（应有 ADD_ROOM/SET_MAINTENANCE/RESTORE 各 1） ====="
curl -sk "$BASE/api/stores/rooms/logs?limit=10" -H "Authorization: Bearer $STORE" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  logs =',len(d))
for l in d:
    if l.get('roomCode')=='E01' or (l.get('bedCode') or '').startswith('E01'):
        print('   ',l['action'],l.get('roomCode'),l.get('bedCode'),l['text'],'by',l['actor'])"

echo "===== W15 数据域：店长写他店 SST99 应 400 ====="
c=$(code -X POST "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" -H 'Content-Type: application/json' \
  -d "{\"storeCode\":\"SST99\",\"roomCode\":\"X99\",\"name\":\"越界房\",\"roomType\":\"TREATMENT\",\"bedCount\":1}")
show "$c"
