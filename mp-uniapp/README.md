# 美研云 C 端小程序（uni-app 工程）

C 端微信小程序，uni-app + Vue 3 + TypeScript + Pinia。由 B 端 H5 原型 `frontend/src/views/mobile/` 的 22 个页面迁移而来，外观严格对齐微信小程序原生（原生导航栏 + 原生 tabBar）。

## 目录结构

```
mp-uniapp/
├─ src/
│  ├─ pages.json            # 22 页路由 + 原生 tabBar + 每页导航栏标题
│  ├─ manifest.json         # 【AppID 配在这里】mp-weixin.appid
│  ├─ main.ts               # createSSRApp + Pinia
│  ├─ App.vue               # onLaunch 拉远程公开配置
│  ├─ uni.scss              # 品牌色变量
│  ├─ pages/                # 22 个页面（home/projects/booking/me/orders/...）
│  ├─ stores/               # C 端精简 store（member/pricelist/appointment/order/coupon/followup）
│  ├─ api/
│  │  ├─ config.ts          # 远程公开配置（启动时从后台拉）
│  │  └─ pay.ts             # 微信支付（服务端统一下单 → uni.requestPayment）
│  └─ utils/
│     ├─ config.ts          # API_BASE / 租户（接口网关域名）
│     ├─ request.ts         # uni.request 封装（token/租户头/401）
│     └─ nav.ts             # navTo/redirectTo/navigateBack/toast
├─ scripts/upload.js        # miniprogram-ci 上传脚本
└─ package.json
```

## 一、本地开发与预览

```bash
cd mp-uniapp
npm install
npm run dev:mp-weixin      # 产物在 dist/dev/mp-weixin
```

然后用「微信开发者工具」导入 `dist/dev/mp-weixin` 目录即可预览（AppID 可先选「测试号」）。

> 开发期在开发者工具「详情 → 本地设置」勾选「不校验合法域名…」，否则请求会被拦。

## 二、AppID 配在哪里？

**AppID 必须配在 `src/manifest.json`：**

```json
{
  "mp-weixin": {
    "appid": "REPLACE_WITH_YOUR_WEAPP_APPID"
  }
}
```

把 `REPLACE_WITH_YOUR_WEAPP_APPID` 换成你在微信公众平台注册的小程序 AppID（`wx` 开头）。

- AppID 是小程序的**身份**，在微信框架加载前就必须确定，**只能构建期写死**，不能运行时从后台获取来改变它。
- B 端「管理后台 → 数据与流程中台 → 小程序与支付」页（`/admin/mp-settings`）也会**登记并展示** AppID，方便运营核对，但那只是记录，真正生效的是 manifest.json。

## 三、AppSecret / 支付密钥配在哪里？（重点）

| 信息 | 配置位置 | 会下发到小程序吗 |
|---|---|---|
| 小程序 AppID | `manifest.json`（构建期） | 本身就是公开身份 |
| AppSecret | **只在服务端密钥库**（B 端配置页录入 → 加密存后端） | ❌ 绝不 |
| 微信支付商户号 mchid | 服务端（B 端配置页录入） | ❌ 绝不 |
| APIv3 密钥 | 服务端密钥库 | ❌ 绝不 |
| 商户私钥证书 apiclient_key.pem | 服务端密钥库 | ❌ 绝不 |
| 品牌名/客服电话/支付开关/主题色/公告 | B 端配置页「公开配置」 | ✅ 启动时拉取 |

**为什么密钥不能放后台让小程序拉？**
AppSecret、APIv3 密钥、商户证书是服务端机密。小程序包可被反编译，一旦下发到端上即可被窃取，导致资金损失。这些值只保存在服务端：

- 运营在 B 端 `/admin/mp-settings` 录入 → 前端只显示**掩码**（如 `wx****abcd`）→ 真实值加密存服务端。
- 微信支付时由**服务端**用这些密钥调微信「统一下单」并签名，小程序只拿到服务端返回的 5 个支付参数（`timeStamp/nonceStr/package/signType/paySign`），调 `uni.requestPayment` 唤起收银台（见 `src/api/pay.ts`）。

**能放后台让小程序自动获取的是「公开配置」**（不敏感、可热更新、无需发版）：
品牌显示名、客服电话、微信支付开关、积分商城开关、邀请开关、主题色、首页公告。
- 后台接口：`GET /api/c/mp/config`（无需登录）
- 小程序在 `App.vue onLaunch` 调 `src/api/config.ts` 的 `useRemoteConfig().load()` 拉取并缓存；拉取失败用内置默认值兜底。
- 运营在 B 端改了这里，小程序下次启动即生效，**不用重新发版**。

## 四、接口域名配置

`src/utils/config.ts` 的 `API_BASE` 改为你的网关域名（https + 已备案）。
上线前在「微信公众平台 → 开发管理 → 开发设置 → 服务器域名」把该域名加到 **request 合法域名**。

## 五、构建与上传发布

### 方式 A：微信开发者工具手动上传
```bash
npm run build:mp-weixin    # 产物在 dist/build/mp-weixin
```
开发者工具导入 `dist/build/mp-weixin` → 点右上角「上传」→ 填版本号/备注 → 到微信公众平台「版本管理」提交审核 → 审核通过后发布。

### 方式 B：CI 自动上传（miniprogram-ci）
1. 微信公众平台 → 开发管理 → 开发设置 → 「小程序代码上传密钥」，下载私钥 `.key`（**存工程外，勿提交 git**）。
2. 配置 CI 机器出口 IP 白名单。
3. 构建后：
```bash
npm run build:mp-weixin
WEAPP_APPID=wx1234567890abcdef \
WEAPP_PRIVATE_KEY=/secure/path/private.wx1234567890abcdef.key \
VERSION=0.1.0 DESC="首次提交" \
npm run upload
```
上传后同样在公众平台「版本管理」提交审核、发布。

## 六、数据来源说明（当前阶段）

- 现阶段 6 个 store 用内置 seed 数据（与 B 端同构），便于无后端时完整演示。
- 后端就绪后，把各 store 的 `seed()` 替换为 `utils/request.ts` 调接口即可（文件头注释已标注建议端点，如 `GET /c/pricelist`、`POST /c/appointments`、`GET /c/orders`）。
- 门店详情、邀请分享、地图导航等目前为占位/mock，待接真实接口与微信能力（`onShareAppMessage`、地图 SDK）。
