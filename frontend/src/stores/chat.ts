import { defineStore } from 'pinia'
import { Client } from '@stomp/stompjs'
import { useUserStore } from '@/stores/user'
import type { ChatMessage, ChatReplyVO, StaffAlert } from '@/types'
import {
  chatHistoryApi,
  chatMessageApi,
  chatRateApi,
  chatSessionStartApi,
  chatStreamMessage,
  chatTransferApi
} from '@/api/chat'

/**
 * 解析 WebSocket 地址：
 * 优先使用 VITE_WS_URL；未配置时按当前页面同源推导。
 * 开发环境由 Vite 代理 /ws 到后端，因此本地与生产都不需要写死后端地址。
 */
function resolveWsUrl(): string {
  const configured = import.meta.env.VITE_WS_URL as string | undefined
  if (configured) return configured
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/chat`
}

const WS_URL = resolveWsUrl()
/** 会话消息队列：后端投递到该用户自己的队列，不再使用可被任意订阅的广播主题 */
const CHAT_QUEUE = '/user/queue/chat'
/** 坐席通知队列：转人工等事件只投递给酒店相关账号 */
const STAFF_QUEUE = '/user/queue/staff'

/**
 * 智能客服状态：会话 / 消息 / WebSocket 连接 / AI 思考中 / 转人工
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: 0 as number,
    messages: [] as ChatMessage[],
    /** disconnected / connecting / connected */
    wsStatus: 'disconnected' as 'disconnected' | 'connecting' | 'connected',
    /** AI 是否正在思考 */
    typing: false,
    /** 流式回复的增量文本：非空时界面展示流式气泡 */
    streamingText: '',
    /** 已转人工 */
    transferred: false,
    transferReason: '',
    /** 会话是否已结束（评价后） */
    ended: false,
    /** STOMP 客户端实例 */
    stompClient: null as Client | null,
    /** 坐席通知（转人工、用户追问） */
    staffAlerts: [] as StaffAlert[],
    /** 是否有未读的坐席通知 */
    hasUnreadAlert: false
  }),
  getters: {
    wsConnected: (state) => state.wsStatus === 'connected'
  },
  actions: {
    /** 创建会话（未创建时） */
    async ensureSession(hotelId?: number) {
      if (this.sessionId) return this.sessionId
      const session = await chatSessionStartApi({ hotelId })
      this.sessionId = session.id
      this.transferred = false
      this.ended = false
      this.messages = []
      return session.id
    },

    /** 加载历史消息 */
    async loadHistory() {
      if (!this.sessionId) return
      this.messages = await chatHistoryApi(this.sessionId)
    },

    /** 建立 STOMP 连接并订阅本会话队列与坐席通知队列 */
    connectWs() {
      const userStore = useUserStore()
      if (!userStore.token || this.wsStatus !== 'disconnected') return
      this.wsStatus = 'connecting'

      const client = new Client({
        brokerURL: `${WS_URL}?token=${encodeURIComponent(userStore.token)}`,
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        onConnect: () => {
          this.wsStatus = 'connected'
          // 会话消息：订阅自己的点对点队列，后端会按用户身份定向投递
          client.subscribe(CHAT_QUEUE, (frame) => {
            try {
              const payload = JSON.parse(frame.body)
              if (payload && payload.type === 'history') {
                this.messages = (payload.data ?? []) as ChatMessage[]
                this.typing = false
                return
              }
              if (payload && payload.type === 'error') {
                this.typing = false
                return
              }
              this.handleReply(payload as ChatReplyVO)
            } catch {
              this.typing = false
            }
          })
          // 坐席通知：仅酒店相关的账号会收到
          client.subscribe(STAFF_QUEUE, (frame) => {
            try {
              this.staffAlerts.unshift(JSON.parse(frame.body) as StaffAlert)
              this.hasUnreadAlert = true
            } catch {
              // 忽略无法解析的负载
            }
          })
        },
        onStompError: () => {
          this.wsStatus = 'disconnected'
        },
        onWebSocketClose: () => {
          this.wsStatus = 'disconnected'
        }
      })
      client.activate()
      // 挂载到 store，便于组件卸载时关闭
      this.stompClient = client
    },

    /** 关闭连接 */
    disconnectWs() {
      const client = this.stompClient
      if (client && typeof client.deactivate === 'function') {
        client.deactivate()
      }
      this.stompClient = null
      this.wsStatus = 'disconnected'
    },

    /** 处理收到的回复（WS 推送或 REST 返回） */
    handleReply(reply: ChatReplyVO) {
      if (!reply) return
      if (reply.needTransfer) {
        this.transferred = true
        this.transferReason = reply.transferReason || ''
      }
      if (reply.reply) {
        this.messages.push({
          id: this.nextMessageId(),
          sessionId: reply.sessionId,
          sender: reply.source === 'human' ? 2 : 1,
          content: reply.reply,
          msgType: 0,
          intentTag: reply.intent,
          createTime: new Date().toISOString()
        })
      }
      this.typing = false
    },

    /**
     * 发送消息：优先 WebSocket，失败回退同步 REST。
     *
     * 界面默认走 {@link sendStream} 获得逐字效果；该方法保留给不支持流式的
     * 环境（例如中间代理会缓冲 SSE）与坐席侧复用。
     */
    async send(content: string) {
      const text = content.trim()
      if (!text || this.typing) return
      if (!this.sessionId) await this.ensureSession()
      this.pushUserMessage(text)
      this.typing = true
      await this.dispatchAnswer(text)
    },

    /**
     * 流式发送消息（SSE）：回答逐字上屏。
     *
     * 流式通道一个分片都没收到时回退同步通道，避免这次提问直接丢失。
     */
    async sendStream(content: string) {
      const text = content.trim()
      if (!text || this.typing) return
      if (!this.sessionId) await this.ensureSession()
      this.pushUserMessage(text)
      this.typing = true
      this.streamingText = ''

      let receivedChunk = false
      const result = await chatStreamMessage(this.sessionId, text, (chunk) => {
        receivedChunk = true
        this.streamingText += chunk
      })

      if (result.ok) {
        // 用服务端返回的完整文本替换增量气泡，保证界面与落库内容一致
        this.streamingText = ''
        const reply = result.reply
        if (reply.needTransfer) {
          this.transferred = true
          this.transferReason = reply.transferReason || ''
        }
        if (reply.reply) {
          this.pushAiMessage(reply.sessionId, reply.reply, reply.intent)
        }
        this.typing = false
        return
      }

      const partial = this.streamingText
      this.streamingText = ''
      this.typing = false
      if (!receivedChunk) {
        // 一个分片都没收到：问题多半出在通道本身，交给同步通道处理（它自己会收尾 typing 与提示）
        await this.dispatchAnswer(text)
        return
      }
      // 已经收到部分内容：保留已生成的部分，再提示后续不完整
      if (partial) {
        this.pushAiMessage(this.sessionId, partial)
      }
      this.pushAiMessage(this.sessionId, `抱歉，${result.error}`)
    },

    /**
     * 走 WebSocket 或同步 REST 获取回答（用户消息需已上屏）。
     *
     * WebSocket 通道下回答由 /user/queue/chat 推送、由 handleReply 收尾；
     * REST 通道下同步返回。
     */
    async dispatchAnswer(text: string) {
      const client = this.stompClient as Client | null
      if (client && this.wsConnected) {
        client.publish({
          destination: '/app/chat.send',
          body: JSON.stringify({ sessionId: this.sessionId, content: text })
        })
        this.armTypingTimeout()
        return
      }
      try {
        const reply = await chatMessageApi(this.sessionId, text)
        this.handleReply(reply)
      } catch {
        this.pushAiMessage(this.sessionId, '抱歉，消息发送失败，请稍后再试。')
        this.typing = false
      }
    },

    /** 乐观上屏用户消息 */
    pushUserMessage(content: string) {
      this.messages.push({
        id: this.nextMessageId(),
        sessionId: this.sessionId,
        sender: 0,
        content,
        msgType: 0,
        createTime: new Date().toISOString()
      })
    },

    /** 上屏 AI 消息 */
    pushAiMessage(sessionId: number, content: string, intent?: string) {
      this.messages.push({
        id: this.nextMessageId(),
        sessionId,
        sender: 1,
        content,
        msgType: 0,
        intentTag: intent,
        createTime: new Date().toISOString()
      })
    },

    /** 转人工 */
    async transfer(reason?: string) {
      if (!this.sessionId) return
      const session = await chatTransferApi(this.sessionId, reason)
      this.transferred = true
      this.transferReason = session.transferReason || '用户要求转接人工客服'
    },

    /** 满意度评价（好评 4-5 / 差评 1-3） */
    async rate(rating: number, comment?: string) {
      if (!this.sessionId) return
      await chatRateApi(this.sessionId, rating, comment)
      this.ended = true
    },

    /** 清除坐席未读标记 */
    clearAlertFlag() {
      this.hasUnreadAlert = false
    },

    /** 重置：切换账号或退出登录时必须调用，避免复用上一个账号的会话 */
    reset() {
      this.disconnectWs()
      this.sessionId = 0
      this.messages = []
      this.transferred = false
      this.transferReason = ''
      this.typing = false
      this.streamingText = ''
      this.ended = false
      this.staffAlerts = []
      this.hasUnreadAlert = false
    },

    /** 消息自增 ID：避免同一毫秒内生成相同 key */
    nextMessageId(): number {
      return Date.now() * 1000 + Math.floor(Math.random() * 1000)
    },

    /**
     * WebSocket 兜底超时：后端未推送回复时解除输入框禁用，
     * 否则一次丢失的推送会让用户永久无法继续输入。
     */
    armTypingTimeout() {
      window.setTimeout(() => {
        if (this.typing) {
          this.typing = false
        }
      }, 60000)
    }
  }
})
