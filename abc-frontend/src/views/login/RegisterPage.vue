<template>
  <div class="login-page">
    <div class="login-card">
      <h2>📝 注册 ABC Anime</h2>
      <el-form @submit.prevent="handleRegister" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" block>
          注 册
        </el-button>
      </el-form>
      <p class="tip">
        已有账号？<router-link to="/login">立即登录</router-link>
      </p>
      <p class="tip">
        <router-link to="/">← 返回首页</router-link>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const username = ref('')
const email = ref('')
const password = ref('')
const loading = ref(false)

const handleRegister = async () => {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.register(username.value, password.value, email.value)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch { /* error handled by interceptor */ }
  loading.value = false
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #fb7299 0%, #ffb6c1 100%);
}
.login-card {
  background: white;
  border-radius: 12px;
  padding: 40px;
  width: 400px;
  box-shadow: 0 12px 40px rgba(0,0,0,0.15);
}
.login-card h2 { text-align: center; margin-bottom: 30px; }
.tip { text-align: center; margin-top: 16px; font-size: 14px; color: var(--text-secondary); }
.tip a { color: var(--primary); }
</style>
