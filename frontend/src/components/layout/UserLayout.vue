<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="logo">酒店预订管理系统</div>
      <el-menu mode="horizontal" :default-active="$route.path" router class="nav" :ellipsis="false">
        <el-menu-item index="/">首页</el-menu-item>
        <el-menu-item index="/orders">我的订单</el-menu-item>
        <el-menu-item v-if="userStore.isStaff" index="/staff">前台工作台</el-menu-item>
        <el-menu-item v-if="userStore.isAdmin" index="/admin/orders">管理后台</el-menu-item>
      </el-menu>
      <div class="user">
        <el-dropdown v-if="userStore.isLogin" @command="onCommand">
          <span class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="account">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-else type="primary" link @click="$router.push('/login')">登录</el-button>
      </div>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
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

const onCommand = async (cmd: string) => {
  if (cmd === 'account') {
    router.push('/account')
    return
  }
  if (cmd === 'logout') {
    try {
      await logoutApi()
    } catch {
      // 忽略登出接口异常，本地清理即可
    }
    // 客服会话与登录态一起清理，避免换账号后复用上一个账号的会话
    chatStore.reset()
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
}
.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #eee;
}
.logo {
  font-size: 20px;
  font-weight: 700;
  color: #409eff;
  white-space: nowrap;
}
.nav {
  flex: 1;
  border-bottom: none;
}
.user-name {
  cursor: pointer;
  color: #333;
}
.main {
  background: #f5f7fa;
}
</style>
