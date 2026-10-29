<template>
  <div class="auth-page">
    <div class="auth-bg"></div>
    <div class="auth-card">
      <h2 class="auth-title">注册 ABC Anime</h2>
      <p class="weak auth-sub">注册后即可追番、发弹幕、同步进度</p>
      <el-form label-position="top" @submit.prevent="handleRegister">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="3-20 个字符" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="邮箱（选填）">
          <el-input v-model="email" placeholder="用于找回账号" size="large" :prefix-icon="Message" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="至少 6 位" size="large" show-password :prefix-icon="Lock" @keyup.enter="handleRegister" />
        </el-form-item>
        <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleRegister">注 册</el-button>
      </el-form>
      <div class="auth-links">
        <router-link :to="{ path: '/login', query: route.query }">已有账号？去登录</router-link>
        <router-link to="/">返回首页</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 注册页：保留原有注册逻辑，补 redirect 透传与深色视觉 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Message, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const username = ref('')
const email = ref('')
const password = ref('')
const loading = ref(false)

async function handleRegister(): Promise<void> {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  if (password.value.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  loading.value = true
  try {
    await userStore.register(username.value, password.value, email.value)
    // 注册接口若直接返回 token 则已登录，否则回登录页
    if (userStore.isLoggedIn()) {
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
      router.replace(redirect || '/')
    } else {
      ElMessage.success('注册成功，请登录')
      router.replace({ path: '/login', query: route.query })
    }
  } catch {
    /* 拦截器已提示 */
  }
  loading.value = false
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  position: relative; overflow: hidden; padding: 20px;
  background: #0b0d12;
}
.auth-bg {
  position: absolute; inset: -20%;
  background: radial-gradient(circle at 20% 20%, rgba(251, 114, 153, .35), transparent 45%),
              radial-gradient(circle at 80% 30%, rgba(180, 101, 218, .3), transparent 45%),
              radial-gradient(circle at 50% 90%, rgba(79, 172, 254, .28), transparent 50%);
  filter: blur(40px);
}
.auth-card {
  position: relative; z-index: 2;
  width: min(420px, 100%);
  padding: 34px 30px 26px;
  border-radius: var(--radius-lg);
  background: rgba(22, 26, 35, .92);
  border: 1px solid var(--c-border);
  box-shadow: 0 20px 60px rgba(0, 0, 0, .5);
  backdrop-filter: blur(10px);
}
.auth-title { font-size: 22px; font-weight: 700; margin-bottom: 6px; text-align: center; }
.auth-sub { font-size: 12.5px; text-align: center; margin-bottom: 20px; }
.submit-btn { width: 100%; margin-top: 6px; }
.auth-links { display: flex; justify-content: space-between; font-size: 13px; margin-top: 16px; }
.auth-links a { color: var(--c-primary); }
@media (max-width: 480px) {
  .auth-card { padding: 24px 18px 20px; }
}
</style>
