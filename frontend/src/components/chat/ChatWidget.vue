<template>
  <div class="chat-widget">
    <!-- 悬浮按钮 -->
    <button v-if="!open" class="chat-fab" @click="onOpen">
      <el-icon :size="26"><ChatDotRound /></el-icon>
      <span v-if="unread" class="chat-badge"></span>
    </button>

    <!-- 聊天窗口 -->
    <transition name="chat-pop">
      <div v-if="open" class="chat-panel">
        <!-- 头部 -->
        <div class="chat-header">
          <div class="chat-header-title">
            <span class="chat-bot-icon">🤖</span>
            <div>
              <div class="chat-bot-name">酒店智能助手</div>
              <div class="chat-bot-status">{{ wsStatusText }}</div>
            </div>
          </div>
          <button class="chat-close" @click="open = false">✕</button>
        </div>

        <!-- 消息区 -->
        <div ref="listRef" class="chat-body">
          <div class="chat-welcome">
            您好！我是酒店智能助手，请问有什么可以帮您？
          </div>
          <div class="chat-quick">
            <button v-for="q in QUICK_QUESTIONS" :key="q.text" class="chat-quick-btn" @click="onSend(q.value)">
              {{ q.text }}
            </button>
          </div>

          <!-- 转人工提示 -->
          <div v-if="chat.transferred" class="chat-transfer-tip">
            <el-icon><Headset /></el-icon>
            {{ chat.transferReason || '已转接人工客服，请稍候' }}
          </div>

          <div v-for="m in chat.messages" :key="m.id" :class="['chat-msg', m.sender === 0 ? 'msg-user' : 'msg-ai']">
            <div class="chat-msg-avatar">
              <el-icon v-if="m.sender === 0" :size="20"><UserFilled /></el-icon>
              <span v-else>🤖</span>
            </div>
            <div class="chat-msg-bubble">{{ m.content }}</div>
          </div>

          <!-- 流式回复：内容随分片增长 -->
          <div v-if="chat.streamingText" class="chat-msg msg-ai">
            <div class="chat-msg-avatar">🤖</div>
            <div class="chat-msg-bubble">{{ chat.streamingText }}</div>
          </div>

          <!-- AI 思考中（尚未收到任何分片时展示） -->
          <div v-if="chat.typing && !chat.streamingText" class="chat-msg msg-ai">
            <div class="chat-msg-avatar">🤖</div>
            <div class="chat-msg-bubble chat-typing">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>

        <!-- 评价（转人工后展示） -->
        <div v-if="chat.transferred && !chat.ended" class="chat-rate">
          <span>本次服务您还满意吗？</span>
          <el-rate v-model="rateValue" size="small" />
          <el-button size="small" type="primary" @click="submitRate">提交评价</el-button>
        </div>

        <!-- 输入区：转人工后仍可继续输入，人工坐席能收到用户追问 -->
        <div class="chat-footer">
          <button class="chat-transfer-btn" :disabled="chat.transferred" @click="onTransfer">转人工</button>
          <el-input
            v-model="input"
            :placeholder="inputPlaceholder"
            :disabled="chat.ended"
            @keyup.enter="onSend(input)"
          />
          <el-button type="primary" :disabled="!input.trim() || chat.typing || chat.ended" @click="onSend(input)">
            发送
          </el-button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Headset, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const userStore = useUserStore()
const chat = useChatStore()

const open = ref(false)
const input = ref('')
const listRef = ref<HTMLElement>()
const rateValue = ref(5)
const unread = ref(false)

const QUICK_QUESTIONS = [
  { text: '查房态', value: '帮我查一下上海酒店最近有没有空房？' },
  { text: '酒店地址', value: '你们酒店地址在哪里？' },
  { text: 'Wi-Fi密码', value: '酒店的Wi-Fi密码是多少？' },
  { text: '送物请求', value: '帮我送两瓶水和一套洗漱用品到房间' }
]

const wsStatusText = computed(() =>
  chat.wsStatus === 'connected' ? '在线' : chat.wsStatus === 'connecting' ? '连接中...' : '离线'
)

const inputPlaceholder = computed(() => {
  if (chat.ended) return '会话已结束，请重新发起咨询'
  return chat.transferred ? '人工客服为您服务，可继续留言...' : '请输入您的问题...'
})

/** 新消息到达或流式文本增长时自动滚动到底部 */
watch(
  () => [chat.messages.length, chat.typing, chat.streamingText],
  async () => {
    await nextTick()
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  }
)

/** 面板收起时收到新消息则显示红点 */
watch(
  () => chat.messages.length,
  (len, prev) => {
    if (!open.value && len > (prev ?? 0)) {
      unread.value = true
    }
  }
)

/** 打开面板：懒创建会话 + 加载历史 + 建立 WebSocket */
const onOpen = async () => {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录后再咨询')
    return
  }
  open.value = true
  unread.value = false
  if (!chat.sessionId) {
    await chat.ensureSession()
    await chat.loadHistory()
    chat.connectWs()
  }
}

/** 发送（typing 防抖：AI 未回复前忽略快速连发） */
const onSend = (text: string) => {
  const content = (text || '').trim()
  if (!content || chat.typing) return
  input.value = ''
  // 走流式通道：回答逐字上屏；通道不可用时 store 内部会回退同步接口
  chat.sendStream(content)
}

/** 转人工 */
const onTransfer = async () => {
  if (chat.transferred) return
  try {
    await ElMessageBox.confirm('确认转接人工客服吗？', '转人工', {
      confirmButtonText: '转接',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await chat.transfer()
}

const submitRate = async () => {
  await chat.rate(rateValue.value, rateValue.value >= 4 ? '满意' : '待改进')
  ElMessage.success('感谢您的评价')
}

onBeforeUnmount(() => {
  chat.disconnectWs()
})
</script>

<style scoped>
.chat-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 3000;
}
.chat-fab {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  background: #409eff;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.4);
  position: relative;
}
.chat-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #f56c6c;
  border: 2px solid #fff;
}
.chat-panel {
  position: absolute;
  right: 0;
  bottom: 64px;
  width: 360px;
  height: 520px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-pop-enter-active,
.chat-pop-leave-active {
  transition: all 0.25s ease;
}
.chat-pop-enter-from,
.chat-pop-leave-to {
  opacity: 0;
  transform: translateY(16px);
}
.chat-header {
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
  padding: 14px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.chat-header-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.chat-bot-icon {
  font-size: 26px;
}
.chat-bot-name {
  font-size: 15px;
  font-weight: 600;
}
.chat-bot-status {
  font-size: 11px;
  opacity: 0.85;
}
.chat-close {
  border: none;
  background: transparent;
  color: #fff;
  font-size: 16px;
  cursor: pointer;
}
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #f5f7fa;
}
.chat-welcome {
  background: #fff;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  color: #333;
  margin-bottom: 8px;
}
.chat-quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}
.chat-quick-btn {
  border: 1px solid #b3d8ff;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.chat-transfer-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #fdf6ec;
  color: #e6a23c;
  font-size: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  margin-bottom: 10px;
}
.chat-msg {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.msg-user {
  flex-direction: row-reverse;
}
.chat-msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #e1eaf3;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.msg-user .chat-msg-avatar {
  background: #409eff;
  color: #fff;
}
.chat-msg-bubble {
  max-width: 72%;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  white-space: pre-wrap;
}
.msg-ai .chat-msg-bubble {
  background: #fff;
  color: #333;
  border-top-left-radius: 2px;
}
.msg-user .chat-msg-bubble {
  background: #409eff;
  color: #fff;
  border-top-right-radius: 2px;
}
.chat-typing {
  display: flex;
  gap: 4px;
  padding: 12px;
}
.chat-typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a0cfff;
  animation: typing 1.2s infinite;
}
.chat-typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.chat-typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes typing {
  0%, 60%, 100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-4px);
  }
}
.chat-rate {
  padding: 8px 14px;
  border-top: 1px solid #eee;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #666;
}
.chat-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #eee;
}
.chat-transfer-btn {
  border: 1px solid #f3d19e;
  background: #fdf6ec;
  color: #e6a23c;
  border-radius: 6px;
  font-size: 12px;
  padding: 6px 8px;
  cursor: pointer;
  white-space: nowrap;
}
</style>
