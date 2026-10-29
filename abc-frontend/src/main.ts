import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
// 【构建修复】旧代码为 'element-plus/dist/locale/zh-cn.mjs'，该路径没有类型声明，
// 在 strict + vue-tsc 下报 TS7016 导致 npm run build 直接失败。改用 es 目录下带 .d.ts 的入口。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// Element Plus 官方暗色主题变量（配合 html.dark 生效），用于青空次元式深色 UI
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './assets/styles/global.css'

const app = createApp(App)

// 全局注册 Element Plus 图标（图标名直接当组件用，如 <el-icon><Search /></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 深色主题：Element Plus 与自定义变量都挂到 html 上
document.documentElement.classList.add('dark')

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
