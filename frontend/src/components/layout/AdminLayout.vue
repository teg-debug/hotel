<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="admin-logo">酒店管理后台</div>
      <el-menu :default-active="$route.path" router background-color="#001529" text-color="#fff" active-text-color="#409eff">
        <el-menu-item index="/admin/hotels">酒店管理</el-menu-item>
        <el-menu-item index="/admin/orders">订单管理</el-menu-item>
        <el-menu-item index="/admin/stats">入住率统计</el-menu-item>
        <el-sub-menu index="/admin/chat">
          <template #title>智能客服</template>
          <el-menu-item index="/admin/chat/dashboard">客服工作台</el-menu-item>
          <el-menu-item index="/admin/chat/analytics">数据看板</el-menu-item>
          <el-menu-item index="/admin/chat/tickets">工单处理</el-menu-item>
          <el-menu-item index="/admin/knowledge">知识库</el-menu-item>
        </el-sub-menu>
        <el-menu-item v-if="userStore.isSysAdmin" index="/admin/users">用户管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="title">经营端</div>
        <div>
          <el-button link @click="$router.push('/')">返回前台</el-button>
          <el-button link @click="onLogout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { logoutApi } from '@/api/auth'

const userStore = useUserStore()
const chatStore = useChatStore()
const router = useRouter()

const onLogout = async () => {
  try {
    // 与用户端保持一致：通知后端失效 token，并清理客服会话
    await logoutApi()
  } catch {
    // 忽略登出接口异常，本地清理即可
  }
  chatStore.reset()
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
}
.aside {
  background: #001529;
}
.admin-logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #eee;
}
.main {
  background: #f5f7fa;
}
</style>
