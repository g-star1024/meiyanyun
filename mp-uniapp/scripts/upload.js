/**
 * 小程序 CI 上传脚本（miniprogram-ci）
 * ---------------------------------------------------------------
 * 用途：构建后把 dist/build/mp-weixin 直接上传到微信后台，
 *      可在「微信公众平台 → 版本管理」看到开发版/体验版，无需手动开开发者工具点上传。
 *
 * 前置准备（只需一次）：
 *   1. 微信公众平台 → 开发管理 → 开发设置 → 小程序代码上传，生成「上传密钥」，
 *      下载私钥文件（.key）保存到本工程外的安全位置（不要提交到 git）。
 *   2. 配置 IP 白名单（CI 机器出口 IP）。
 *   3. 把 appid 和私钥路径通过环境变量传入（见下）。
 *
 * 用法：
 *   npm run build:mp-weixin
 *   WEAPP_APPID=wx1234567890abcdef \
 *   WEAPP_PRIVATE_KEY=/secure/path/private.wx1234567890abcdef.key \
 *   VERSION=0.1.0 DESC="首次提交" \
 *   node scripts/upload.js
 */
const path = require('path')
const ci = require('miniprogram-ci')

const APPID = process.env.WEAPP_APPID
const PRIVATE_KEY_PATH = process.env.WEAPP_PRIVATE_KEY
const VERSION = process.env.VERSION || '0.1.0'
const DESC = process.env.DESC || `CI 构建 ${new Date().toISOString().slice(0, 16)}`
const PROJECT_DIR = path.resolve(__dirname, '../dist/build/mp-weixin')

async function main() {
  if (!APPID) throw new Error('缺少环境变量 WEAPP_APPID（小程序 AppID）')
  if (!PRIVATE_KEY_PATH) throw new Error('缺少环境变量 WEAPP_PRIVATE_KEY（上传私钥 .key 路径）')

  const project = new ci.Project({
    appid: APPID,
    type: 'miniProgram',
    projectPath: PROJECT_DIR,
    privateKeyPath: PRIVATE_KEY_PATH,
    ignores: ['node_modules/**/*'],
  })

  const result = await ci.upload({
    project,
    version: VERSION,
    desc: DESC,
    setting: {
      es6: true,
      minify: true,
      autoPrefixWXSS: true,
    },
    onProgressUpdate: console.log,
  })
  console.log('上传完成：', result)
}

main().catch((err) => {
  console.error('上传失败：', err)
  process.exit(1)
})
