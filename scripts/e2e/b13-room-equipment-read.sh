#!/bin/bash
# B13 只读 E2E（seed 栈 https://localhost:18443）
set -uo pipefail
BASE="https://localhost:18443"
j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(json.dumps(d,ensure_ascii=False,indent=None))"; }
login(){ curl -sk -X POST "$BASE/api/org/auth/login" -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"$1\",\"password\":\"meiyun123\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])"; }

echo "===== R0 无 token 应 401 ====="
curl -sk -o /dev/null -w "GET /rooms  no-token -> %{http_code}\n" "$BASE/api/stores/rooms"
curl -sk -o /dev/null -w "GET /equipments no-token -> %{http_code}\n" "$BASE/api/stores/equipments"

STORE=$(login SE001)
SUPER=$(login SE101)
echo "=== SE001 perms check ==="
python3 - "$STORE" <<'PY'
import sys,base64,json
t=sys.argv[1].split('.')[1]; t+='='*(-len(t)%4)
c=json.loads(base64.urlsafe_b64decode(t))
perms=set(c.get('perms',[]))
for k in ['room:view','room:edit','equipment:view','equipment:edit']:
    print(f"  {k} = {k in perms}")
print("  storeCode =", c.get('storeCode'), " scope =", c.get('scope'))
PY

echo "===== R1 店长查房间（含床位嵌套）====="
curl -sk "$BASE/api/stores/rooms" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('rooms =',len(d));tot=0;maint=0
for r in d:
    b=r['beds'];tot+=len(b);maint+=sum(1 for x in b if x['maintStatus']=='MAINTENANCE')
print('beds total =',tot,' maint =',maint)
print('sample:',r and d[0]['roomCode'],d[0]['name'],d[0]['roomType'],'beds=',[x['bedCode'] for x in d[0]['beds']])
mm=[ (x['bedCode'],x['maintReason']) for r in d for x in r['beds'] if x['maintStatus']=='MAINTENANCE']
print('maint beds =',mm)"

echo "===== R2 房间过滤 type=TREATMENT ====="
curl -sk "$BASE/api/stores/rooms?type=TREATMENT" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('TREATMENT rooms =',len(d),[r['roomCode'] for r in d])"

echo "===== R3 店长查设备台账 ====="
curl -sk "$BASE/api/stores/equipments" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('equipments =',len(d))
for e in d: print(' ',e['assetNo'],e['name'],e['category'],e['status'],'amt=',e['purchaseAmount'],'depr=',e['depreciated'],'recs=',len(e['records']))"

echo "===== R4 设备过滤 status=REPAIRING + keyword ====="
curl -sk "$BASE/api/stores/equipments?status=REPAIRING" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('REPAIRING =',[(e['assetNo'],e['name']) for e in d])"
curl -sk "$BASE/api/stores/equipments?category=LASER" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('LASER =',[e['assetNo'] for e in d])"

echo "===== R5 操作日志（基线应为空或历史）====="
curl -sk "$BASE/api/stores/rooms/logs?limit=5" -H "Authorization: Bearer $STORE" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('logs =',len(d))"

echo "===== R6 超管查（不带 storeCode，超管放行 null）====="
curl -sk "$BASE/api/stores/rooms" -H "Authorization: Bearer $SUPER" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('super rooms =',len(d))"
curl -sk "$BASE/api/stores/equipments" -H "Authorization: Bearer $SUPER" \
 | python3 -c "import sys,json;d=json.load(sys.stdin);print('super equipments =',len(d))"
