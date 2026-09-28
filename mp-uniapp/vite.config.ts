import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { resolve } from 'path'

// uni-app 小程序构建配置
// 注意：uni 插件需放在 plugins 首位
export default defineConfig({
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  plugins: [uni()],
  server: {
    proxy: {
      '/c': {
        target: 'http://localhost:18090',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/c/, '/api/c'),
      },
    },
  },
})
