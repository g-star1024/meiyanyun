/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发期将 /api 反代到国密网关，secure:false 跳过自签 SM2 证书校验（仅本地开发）。
// 网关目标默认正式栈 https://localhost:8443；联调种子库时可用环境变量切换到 seed 网关：
//   VITE_GATEWAY_TARGET=https://localhost:18443 pnpm dev
const gatewayTarget = process.env.VITE_GATEWAY_TARGET || 'https://localhost:8443'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 单元测试：node 环境跑 store/适配层纯逻辑（*.spec.ts）。
  // 组件/DOM 测试未来可用 environment: 'jsdom' 单独拆分。
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: gatewayTarget,
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
