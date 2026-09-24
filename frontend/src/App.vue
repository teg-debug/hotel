<template>
  <div>
    <router-view />
    <!-- 用户端智能客服浮窗（管理端/登录页不显示） -->
    <ChatWidget v-if="showChatWidget" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ChatWidget from '@/components/chat/ChatWidget.vue'

const route = useRoute()

const showChatWidget = computed(() => {
  const p = route.path
  return !p.startsWith('/admin') && !p.startsWith('/staff') && p !== '/login' && p !== '/register'
})
</script>
