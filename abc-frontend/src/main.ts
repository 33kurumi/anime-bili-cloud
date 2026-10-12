import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import Hls from 'hls.js'
import App from './App.vue'
import router from './router'
import './assets/styles/global.css'

// DPlayer 播 HLS(m3u8) 依赖全局 window.Hls，DPlayer 自身不打包 hls.js，需在此挂载
;(window as any).Hls = Hls

const app = createApp(App)

// Register all icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, {
  locale: zhCn
})

app.mount('#app')
