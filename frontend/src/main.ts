import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import vPerm from './directives/vPerm'
import { useStoreContext } from './stores/storeContext'
import { useAuthStore } from './stores/auth'
import './styles/tokens.css'

const pinia = createPinia()
const app = createApp(App).use(pinia).use(router).directive('perm', vPerm)

// 登录会话恢复（同步、立即可用）：回填 token 与角色，
// 保证首次路由守卫与 axios 拦截器在挂载前即持有登录态。
useAuthStore(pinia).restoreSession()

// 权限矩阵预热（异步、不阻塞挂载）：离线 ?as= 演示也以服务端 rolePermissions 为真源，
// 拉取失败时 store 内部静默回退前端硬编码。
void useAuthStore(pinia).loadMatrix()

// 全局门店上下文：先从 localStorage 恢复当前门店（同步、立即可用），
// 再异步拉真实门店列表；业务页据此按门店隔离数据。
useStoreContext(pinia).init()

app.mount('#app')
