<template>
  <div class="chat-dashboard">
    <!-- 左列：会话列表 -->
    <div class="sessions-panel">
      <div class="panel-header">
        <span class="panel-title">
          会话列表
          <el-badge is-dot :hidden="!chat.hasUnreadAlert" class="alert-dot" />
          <el-tag v-if="chat.hasUnreadAlert" size="small" type="danger" class="alert-tag">新转人工</el-tag>
        </span>
        <el-badge :value="pendingCount" :max="99" :hidden="pendingCount === 0" type="danger" class="pending-badge">
          <span class="pending-anchor">待处理</span>
        </el-badge>
      </div>

      <el-tabs v-model="activeTab" class="filter-tabs" @tab-change="loadSessions">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="进行中" name="active" />
        <el-tab-pane label="已转人工" name="transferred" />
        <el-tab-pane label="已结束" name="ended" />
      </el-tabs>

      <div v-loading="loading" class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: current?.id === s.id }"
          @click="selectSession(s)"
        >
          <div class="row1">
            <span class="name">{{ s.username || '游客' }}</span>
            <span class="time">{{ fmtTime(s.startTime) }}</span>
          </div>
          <div class="row2">
            <el-tag v-if="s.pending" size="small" type="danger">待处理</el-tag>
            <el-tag v-else-if="s.status === 0" size="small" type="primary">进行中</el-tag>
            <el-tag v-else-if="s.status === 2" size="small" type="warning">已转人工</el-tag>
            <el-tag v-else size="small" type="info">已结束</el-tag>
            <span class="duration">{{ fmtDuration(s.durationMin) }}</span>
            <span class="msg-count">{{ s.messageCount }} 条</span>
          </div>
          <div v-if="s.tag" class="row3">
            <el-tag size="small" type="success">{{ s.tag }}</el-tag>
          </div>
        </div>
        <el-empty v-if="!loading && sessions.length === 0" description="暂无会话" :image-size="60" />
      </div>

      <div class="pager">
        <el-pagination
          small
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          v-model:current-page="page"
          @current-change="loadSessions"
        />
      </div>
    </div>

    <!-- 右列：当前会话详情 -->
    <div class="detail-panel">
      <template v-if="current">
        <div class="detail-header">
          <div class="dh-left">
            <span class="session-no">{{ current.sessionNo }}</span>
            <el-tag v-if="current.tag" size="small" type="success">{{ current.tag }}</el-tag>
          </div>
          <div class="dh-right">
            <span v-if="current.rating" class="rating">满意度 {{ current.rating }}★</span>
            <el-tag v-if="current.transferFlag === 1" size="small" type="warning">已转人工</el-tag>
          </div>
        </div>

        <div class="detail-body">
          <!-- 消息区 -->
          <div ref="msgBox" class="messages">
            <div v-for="m in messages" :key="m.id" class="msg" :class="senderCls(m.sender)">
              <el-avatar :size="30" class="avatar" :class="'avatar-' + m.sender">
                {{ avatarText(m.sender) }}
              </el-avatar>
              <div class="bubble">
                <div class="bubble-content">{{ m.content }}</div>
                <div class="bubble-time">{{ fmtMsgTime(m.createTime) }}</div>
              </div>
            </div>
            <el-empty v-if="messages.length === 0" description="暂无消息" :image-size="60" />
          </div>

          <!-- 回复输入区 -->
          <div class="reply-bar">
            <div class="quick">
              <span class="label">快捷回复：</span>
              <el-select
                v-model="quickTemplate"
                size="small"
                clearable
                placeholder="选择快捷回复模板"
                style="width: 320px"
                @change="applyQuick"
              >
                <el-option v-for="t in quickReplies" :key="t" :label="t" :value="t" />
              </el-select>
            </div>
            <div class="reply-input">
              <el-input
                v-model="replyText"
                type="textarea"
                :rows="2"
                resize="none"
                placeholder="输入回复内容，Enter 发送"
                @keydown.enter.prevent="sendReply"
              />
              <el-button type="primary" class="send-btn" :loading="sending" @click="sendReply">
                发送
              </el-button>
            </div>
            <div class="tag-bar">
              <span class="label">会话标签：</span>
              <el-tag
                v-for="t in ALL_TAGS"
                :key="t"
                class="tag-item"
                :class="{ 'tag-active': current.tag === t }"
                :effect="current.tag === t ? 'dark' : 'plain'"
                @click="setTag(t)"
              >
                {{ t }}
              </el-tag>
            </div>
          </div>
        </div>

        <!-- 用户信息侧边栏 -->
        <div class="user-panel">
          <div class="user-title">用户信息</div>
          <el-descriptions :column="1" size="small" class="user-desc">
            <el-descriptions-item label="用户名">{{ userDetail.username || '游客' }}</el-descriptions-item>
            <el-descriptions-item label="会员等级">
              <el-tag size="small" :type="memberTagType(userDetail.memberLevel)">
                {{ MEMBER_LEVEL_TEXT[userDetail.memberLevel] || '普通' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="历史订单">{{ userDetail.orderCount }} 单</el-descriptions-item>
            <el-descriptions-item label="消息数">{{ current.messageCount }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ fmtTime(current.startTime) }}</el-descriptions-item>
            <el-descriptions-item label="会话时长">{{ fmtDuration(current.durationMin) }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </template>
      <el-empty v-else description="请选择左侧会话" class="detail-empty" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  adminChatReplyApi,
  adminSessionDetailApi,
  adminSessionsApi,
  adminSessionTagApi
} from '@/api/chat'
import type { ChatAdminSession, ChatMessage, ChatSessionDetail } from '@/types'
import { MEMBER_LEVEL_TEXT } from '@/types'
import { useChatStore } from '@/stores/chat'

/** 客服工作台与 WebSocket 共用同一个 chat store：坐席通知由 store 统一接收 */
const chat = useChatStore()

const ALL_TAGS = ['已解决', '转技术', '投诉'] as const

/** 快捷回复模板 */
const quickReplies = [
  '您好，请问有什么可以帮您？',
  '正在为您查询，请稍候。',
  '您的需求已登记，我们会尽快为您安排。',
  '如遇紧急情况，请拨打前台电话，感谢您的理解。',
  '感谢您的反馈，我们已记录并安排相关人员跟进。'
]

// ---------- 会话列表 ----------
const loading = ref(false)
const sessions = ref<ChatAdminSession[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const activeTab = ref('all')

const pendingCount = computed(() => sessions.value.filter((s) => s.pending).length)

const TAB_STATUS: Record<string, number | undefined> = {
  all: undefined,
  active: 0,
  transferred: 2,
  ended: 1
}

const loadSessions = async () => {
  loading.value = true
  try {
    const res = await adminSessionsApi({
      status: TAB_STATUS[activeTab.value],
      page: page.value,
      size: size.value
    })
    sessions.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error((e as Error).message || '会话列表加载失败')
  } finally {
    loading.value = false
  }
}

// ---------- 会话详情 ----------
const current = ref<ChatAdminSession | null>(null)
const messages = ref<ChatMessage[]>([])
const userDetail = reactive<{ username: string; memberLevel: number; orderCount: number }>({
  username: '',
  memberLevel: 0,
  orderCount: 0
})
const msgBox = ref<HTMLElement | null>(null)

const selectSession = async (s: ChatAdminSession) => {
  current.value = s
  // 已进入会话详情，视为已处理坐席通知
  chat.clearAlertFlag()
  await loadDetail(s.id)
}

const loadDetail = async (sessionId: number) => {
  try {
    const detail: ChatSessionDetail = await adminSessionDetailApi(sessionId)
    messages.value = detail.messages
    userDetail.username = detail.username
    userDetail.memberLevel = detail.memberLevel
    userDetail.orderCount = detail.orderCount
    scrollToBottom()
  } catch {
    ElMessage.error('会话详情加载失败')
  }
}

const scrollToBottom = () => {
  requestAnimationFrame(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

watch(
  () => messages.value.length,
  () => scrollToBottom()
)

// ---------- 人工回复 ----------
const replyText = ref('')
const quickTemplate = ref('')
const sending = ref(false)

const applyQuick = (val: string) => {
  if (val) replyText.value = val
}

const sendReply = async () => {
  const content = replyText.value.trim()
  if (!content || !current.value) return
  sending.value = true
  try {
    await adminChatReplyApi(current.value.id, content)
    // 乐观上屏人工消息，随后刷新详情与列表
    messages.value.push({
      id: Date.now(),
      sessionId: current.value.id,
      sender: 2,
      content,
      msgType: 0,
      createTime: new Date().toISOString()
    })
    replyText.value = ''
    quickTemplate.value = ''
    current.value.messageCount = (current.value.messageCount || 0) + 1
    scrollToBottom()
    loadSessions()
  } catch (e) {
    ElMessage.error((e as Error).message || '回复发送失败')
  } finally {
    sending.value = false
  }
}

// ---------- 会话标签 ----------
const setTag = async (tag: string) => {
  if (!current.value) return
  if (current.value.tag === tag) return
  try {
    await adminSessionTagApi(current.value.id, tag)
    current.value.tag = tag
    if (tag === '已解决') {
      current.value.status = 1
      loadSessions()
    }
    ElMessage.success(`已标记「${tag}」`)
  } catch (e) {
    ElMessage.error((e as Error).message || '标签设置失败')
  }
}

// ---------- 展示辅助 ----------
const senderCls = (sender: number) => (sender === 0 ? 'msg-user' : 'msg-ai')
const avatarText = (sender: number) => (sender === 0 ? '客' : sender === 1 ? 'AI' : '服')
const memberTagType = (level: number) => (level >= 2 ? 'warning' : level === 1 ? 'primary' : 'info')

const fmtTime = (t?: string) => {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const fmtMsgTime = (t: string) => {
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const fmtDuration = (min?: number) => {
  if (!min && min !== 0) return ''
  if (min < 60) return `${min} 分钟`
  const h = Math.floor(min / 60)
  const m = min % 60
  return m ? `${h} 小时 ${m} 分` : `${h} 小时`
}

// ---------- 生命周期：WebSocket 通知即时刷新 + 轮询兜底 + 清理 ----------
let timer: ReturnType<typeof setInterval> | undefined

/**
 * 收到坐席通知（转人工 / 用户追问）时立即刷新会话列表，
 * 低频轮询仅作为 WebSocket 断线时的兜底。
 */
watch(
  () => chat.staffAlerts.length,
  () => {
    loadSessions()
    const latest = chat.staffAlerts[0]
    if (latest && current.value && latest.sessionId === current.value.id) {
      loadDetail(latest.sessionId)
    }
  }
)

onMounted(() => {
  chat.connectWs()
  loadSessions()
  timer = setInterval(() => {
    if (!loading.value) loadSessions()
  }, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.chat-dashboard {
  display: flex;
  gap: 16px;
  height: calc(100vh - 180px);
  min-height: 520px;
}

/* 左列 */
.sessions-panel {
  width: 340px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 0;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
}
.alert-dot {
  margin-left: 6px;
}
.alert-tag {
  margin-left: 4px;
}
.pending-anchor {
  padding: 0 4px;
  font-size: 12px;
}
.filter-tabs {
  padding: 0 12px;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}
.session-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 6px;
}
.session-item:hover {
  background: #f5f7fa;
}
.session-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}
.row1 {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.name {
  font-weight: 600;
  font-size: 14px;
}
.time {
  color: #909399;
  font-size: 12px;
}
.row2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.duration {
  color: #606266;
  font-size: 12px;
}
.msg-count {
  color: #909399;
  font-size: 12px;
  margin-left: auto;
}
.row3 {
  margin-top: 6px;
}
.pager {
  padding: 8px 12px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: center;
}

/* 右列 */
.detail-panel {
  flex: 1;
  background: #fff;
  border-radius: 8px;
  display: flex;
  overflow: hidden;
  position: relative;
}
.detail-header {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  background: #fff;
}
.dh-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.session-no {
  font-size: 15px;
  font-weight: 600;
}
.dh-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rating {
  color: #e6a23c;
  font-size: 13px;
}
.detail-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  margin-top: 52px;
  padding-bottom: 12px;
  overflow: hidden;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
}
.msg {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  align-items: flex-start;
}
.msg-user {
  flex-direction: row-reverse;
}
.avatar {
  flex-shrink: 0;
  background: #c0c4cc;
  font-size: 13px;
}
.avatar-0 {
  background: #409eff;
}
.bubble {
  max-width: 70%;
}
.bubble-content {
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  background: #f0f2f5;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-user .bubble-content {
  background: #409eff;
  color: #fff;
}
.bubble-time {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 4px;
}
.msg-user .bubble-time {
  text-align: right;
}

/* 回复区 */
.reply-bar {
  border-top: 1px solid #f0f0f0;
  padding: 10px 16px;
  flex-shrink: 0;
}
.quick {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.label {
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
  margin-right: 6px;
}
.reply-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.send-btn {
  height: 52px;
}
.tag-bar {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
.tag-item {
  cursor: pointer;
  margin-right: 8px;
}

/* 用户信息侧栏 */
.user-panel {
  position: absolute;
  top: 52px;
  right: 0;
  bottom: 0;
  width: 200px;
  border-left: 1px solid #f0f0f0;
  padding: 12px;
  overflow-y: auto;
  background: #fafbfc;
}
.user-title {
  font-weight: 600;
  margin-bottom: 10px;
  font-size: 13px;
}
.user-desc :deep(.el-descriptions__label) {
  color: #909399;
}
.detail-empty {
  margin: auto;
}
</style>
