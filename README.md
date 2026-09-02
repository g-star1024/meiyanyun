# 美研云 · 门店中台（MeiYanYun Store Middle-Platform）

医美连锁经营管理平台：面向连锁医美机构的 **B 端门店中台** + **C 端小程序**，覆盖集团总部管控（M1）、门店运营（M2）、客户资产 CRM（M3）、交易履约（M4）、营销（M5）、财务（M6）与平台权限安全（M7）等业务域。

四层架构：

```
C 端小程序（uni-app） → B 端中台（Vue3 + TS） → 国密网关（Go） → 后端微服务（Java 17 + Spring Boot 3）
```

## 技术栈

| 层 | 技术 |
|---|---|
| B 端中台 | Vue 3 + TypeScript + Vite + Pinia + Vue Router + Axios |
| C 端 | uni-app（微信小程序 / H5） |
| 网关 | Go 1.21+，国密 TLS（SM2 证书 / SM4 会话 / SM3 摘要，GM/T 0024），反向代理 + 鉴权透传 |
| 后端 | Java 17 + Spring Boot 3 + Spring JDBC + JWT，Maven 多模块 |
| 数据库 | PostgreSQL 15 |
| 缓存 / 消息 | Redis 7、RocketMQ |
| 搜索 / 对象存储 | Elasticsearch 8、MinIO（S3 兼容） |
| 部署 | Docker + Docker Compose（双栈：正式栈 / 测试栈） |

## 仓库结构（monorepo）

```
meiyun-platform/
├─ gateway/                 # Go 国密网关（国密 TLS 终止 + 路由反向代理）
├─ backend/                 # Java 微服务（Maven 多模块）
│  ├─ meiyun-common/        # 公共库：双签引擎、append-only 审计哈希链、金额工具
│  ├─ meiyun-security/      # 统一安全：JWT、RBAC 注解鉴权、数据权限、中文校验
│  ├─ org-service/          # 组织 / 员工 / 角色权限 / 登录认证（/api/org）
│  ├─ store-service/        # 门店主数据 / 六大区（/api/stores）
│  ├─ customer-service/     # 客户档案 / 积分商城（/api/customer）
│  ├─ txn-service/          # 交易：预约、订单、退款退卡、双签工单（/api/txn）
│  ├─ marketing-service/    # 营销：优惠券 / 活动 / 套餐（/api/marketing）
│  ├─ finance-service/      # 财务（只读镜像）（/api/finance）
│  ├─ audit-service/        # 审计日志查询与哈希链校验（/api/audit）
│  ├─ db/                   # 数据库迁移与种子数据（migration / seed）
│  └─ Dockerfile            # 后端服务统一镜像构建（--build-arg SERVICE=<svc>）
├─ frontend/                # B 端中台（Vue3）：页面、组件、Pinia store、API 层
├─ mp-uniapp/               # C 端小程序（uni-app）
├─ scripts/                 # 测试库初始化 / 重置 / 网络接入脚本
├─ docker-compose.yml       # 中间件（PG / Redis / RocketMQ / ES / MinIO）
├─ docker-compose.app.yml   # 正式栈（前端 8080 / 网关 8443 / 后端 8082-8088）
├─ docker-compose.seed.yml  # 测试栈（前端 18080 / 网关 18443 / 后端 18082-18088）
├─ dev-up.sh                # 本地开发：前端 dev server + 网关一键启停
└─ .github/workflows/       # CI（三组件构建 + 单测）
```

## 快速开始（Docker Compose）

双栈共用同一套镜像 tag（`meiyun/<service>:latest`），容器名与宿主端口互不冲突，可同时运行。

```bash
# 1) 构建镜像（后端按服务构建，前端 / 网关各自构建）
docker build -t meiyun/org-service:latest -f backend/Dockerfile --build-arg SERVICE=org-service .
# 其余后端服务同理：customer / txn / marketing / finance / store / audit
docker build -t meiyun/gateway:latest  -f gateway/Dockerfile  gateway
docker build -t meiyun/frontend:latest -f frontend/Dockerfile frontend

# 2) 启动中间件
docker compose -f docker-compose.yml up -d

# 3) 启动正式栈  → http://127.0.0.1:8080/
docker compose -f docker-compose.app.yml up -d

# 4) 启动测试栈  → http://127.0.0.1:18080/
docker compose -f docker-compose.seed.yml up -d
```

测试库一键初始化 / 重置（仅影响测试栈数据库）：

```bash
./scripts/setup-seed-db.sh     # 初始化种子库
./scripts/reset-seed-db.sh     # 重置回基线
```

## 本地开发

```bash
# 后端（须在 backend 目录、单模块带 -am 连带依赖）
cd backend && mvn -q -pl org-service -am package -DskipTests
java -jar org-service/target/org-service-1.0.0-SNAPSHOT.jar --server.port=8086

# 网关（Go，自签 SM2 证书运行时生成）
cd gateway && go build -o meiyun-gateway . && ./meiyun-gateway   # 监听 :8443

# B 端前端（Node 18+，推荐 pnpm）
cd frontend && pnpm install && pnpm dev        # dev server（Vite proxy 到网关）
cd frontend && pnpm build                      # vue-tsc --noEmit && vite build

# 一键拉起前端 + 网关（幂等）
./dev-up.sh        # 启动并探活
./dev-up.sh stop   # 停止
```

> 本地直连提示：网关为自签国密证书，curl 需 `-k`；如本机存在 HTTP 代理，本地请求需 `--noproxy '*'`。

## 服务端口与路由

| 服务 | 正式栈端口 | 测试栈端口 | 网关前缀 |
|---|---|---|---|
| 前端（Nginx） | 8080 | 18080 | — |
| 网关（国密 TLS） | 8443 | 18443 | — |
| customer-service | 8082 | 18082 | /api/customer |
| txn-service | 8083 | 18083 | /api/txn |
| audit-service | 8084 | 18084 | /api/audit |
| store-service | 8085 | 18085 | /api/stores |
| org-service | 8086 | 18086 | /api/org |
| finance-service | 8087 | 18087 | /api/finance |
| marketing-service | 8088 | 18088 | /api/marketing |

前端经 Nginx `/api` 反向代理到网关；网关按「精确前缀 + 尾斜杠」双写路由，新增服务在路由表追加一行即可。

## 登录与测试账号

- 登录方式：工号 + 密码，或登录页角色快捷登录（演示环境）。
- 统一演示密码：`meiyun123`。
- 测试栈（18080）测试工号：集团管理员 `SE101`、区域经理 `SE102`、门店店长 `SE001`、咨询顾问 `SE002`、医生 `SE003`、前台收银 `SE105` 等。
- 正式栈（8080）测试工号：集团管理员 `E014`、区域经理 `E011`、门店店长 `E005`、咨询顾问 `E004`、医生 `E001`、前台收银 `E002` 等。

> 演示口令与种子数据仅用于本地 / 测试环境，生产环境请通过环境变量覆盖数据源与密钥配置。

## 业务红线（落代码层）

1. **双签**：退款 / 退卡 / 耗材领用 / 报损 / 现金交接不看金额一律至少双签；硬校验两签不同人、不同岗位序列、医疗类第二签须持执业资质、双时间戳 + 签章 + 审计。
2. **禁忌硬阻断**：医疗禁忌确诊 → 按钮禁用 + 原因落屏 + 双签解锁（非提示 / 警告）。
3. **审计不可篡改**：`audit_log` 仅 INSERT / SELECT，无 UPDATE / DELETE，SHA-256 链式哈希。
4. **资金只读**：财务域镜像无写接口。
5. **退款 / 退卡隔离**：独立表、独立序列；手续费手动窗口，系统强制「退卡金额 + 手续费 = 卡内余额」。

## 权限与安全

- JWT（HMAC-SHA256）登录态，claims 携带员工、角色、门店、数据域、权限码。
- RBAC 角色权限 + 四级数据权限（集团 / 区域 / 门店 / 个人），后端统一拦截器 + 注解鉴权，前端 `v-perm` 指令做按钮 / 字段级控制。
- 全链路操作审计（append-only 哈希链），敏感字段（如手机号）按权限脱敏，后端校验错误信息统一中文返回。

## CI

`.github/workflows/ci.yml` 在 push 后自动执行网关、后端、前端三组件的构建与单元测试。
