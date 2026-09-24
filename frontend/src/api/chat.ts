import request from './request'
import { useUserStore } from '@/stores/user'
import type {
  ChatAdminSession,
  ChatMessage,
  ChatReplyVO,
  ChatSession,
  ChatSessionDetail,
  HotQuestionVO,
  KnowledgeEntry,
  PageResult,
  ResolutionStats,
  ServiceTicketVO,
  TrendPoint
} from '@/types'

// ==================== 用户端对话 ====================

/** 创建会话 */
export const chatSessionStartApi = (data: { hotelId?: number; source?: number }) =>
  request.post<any, ChatSession>('/chat/session/start', data)

/** 发送消息（REST 兜底，WebSocket 不可用时使用） */
export const chatMessageApi = (sessionId: number, content: string, timeout = 60000) =>
  request.post<any, ChatReplyVO>(`/chat/session/${sessionId}/message`, { content }, { timeout })

/** 流式发送结果：成功带回完整回复，失败带回错误文案 */
export type ChatStreamResult =
  | { ok: true; reply: ChatReplyVO }
  | { ok: false; error: string }

/**
 * 流式发送消息（SSE）。
 *
 * 用 fetch 而不是 axios：axios 在浏览器端拿不到增量响应体，
 * 而这里需要边读边把分片交给 onChunk 渲染。事件类型为
 * chunk（文本分片）/ done（完整结果）/ error（错误消息）。
 *
 * @param onChunk 每收到一个文本分片回调一次
 */
export async function chatStreamMessage(
  sessionId: number,
  content: string,
  onChunk: (text: string) => void
): Promise<ChatStreamResult> {
  const token = useUserStore().token
  let response: Response
  try {
    response = await fetch(`/api/v1/chat/session/${sessionId}/message/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ content })
    })
  } catch {
    return { ok: false, error: '网络异常，请稍后重试。' }
  }

  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const payload = await response.json()
      if (payload?.msg) message = payload.msg
    } catch {
      // 保留默认文案
    }
    return { ok: false, error: message }
  }
  if (!response.body) {
    return { ok: false, error: '当前浏览器不支持流式响应。' }
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let failure: string | null = null

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行分隔事件；最后一段可能不完整，留到下一轮
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        const parsed = parseSseFrame(frame)
        if (!parsed) continue
        if (parsed.event === 'chunk') {
          const text = (parsed.data as { content?: string })?.content
          if (text) onChunk(text)
        } else if (parsed.event === 'done') {
          return { ok: true, reply: parsed.data as ChatReplyVO }
        } else if (parsed.event === 'error') {
          failure = (parsed.data as { msg?: string })?.msg || '服务异常，请稍后重试。'
        }
      }
    }
  } catch {
    return { ok: false, error: '流式响应中断，请稍后重试。' }
  }

  return { ok: false, error: failure || '流式响应意外结束，请稍后重试。' }
}

/** 解析一个 SSE 事件块，返回事件名与已反序列化的数据 */
function parseSseFrame(frame: string): { event: string; data: unknown } | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }
  if (!dataLines.length) return null
  try {
    return { event, data: JSON.parse(dataLines.join('\n')) }
  } catch {
    return null
  }
}

/** 会话历史 */
export const chatHistoryApi = (sessionId: number) =>
  request.get<any, ChatMessage[]>(`/chat/session/${sessionId}/history`)

/** 转人工 */
export const chatTransferApi = (sessionId: number, reason?: string) =>
  request.post<any, ChatSession>(`/chat/session/${sessionId}/transfer`, { reason })

/** 满意度评价 */
export const chatRateApi = (sessionId: number, rating: number, comment?: string) =>
  request.post<any, ChatSession>(`/chat/session/${sessionId}/rate`, { rating, comment })

// ==================== 知识库管理（经营者/管理员） ====================

export const knowledgeImportApi = (data: {
  hotelId?: number
  category?: string
  items: { question: string; answer: string }[]
}) => request.post<any, number>('/chat/knowledge/import', data)

export const knowledgeUploadApi = (file: File, hotelId?: number, category?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (hotelId) form.append('hotelId', String(hotelId))
  if (category) form.append('category', category)
  return request.post<any, number>('/chat/knowledge/upload', form)
}

export const knowledgeDeleteApi = (id: number) =>
  request.delete<any, void>(`/chat/knowledge/${id}`)

/** 更新知识条目（传 hotelId 为空表示平台级知识，仅系统管理员可维护） */
export const knowledgeUpdateApi = (
  id: number,
  data: {
    question: string
    answer: string
    category?: string
    similarityThreshold?: number
    status?: number
  }
) => request.put<any, KnowledgeEntry>('/chat/knowledge/' + id, data)

export const knowledgePageApi = (params: { hotelId?: number; page: number; size: number }) =>
  request.get<any, PageResult<KnowledgeEntry>>('/chat/knowledge/page', { params })

// ==================== 服务工单（前台/经营者/管理员） ====================

/** 工单分页列表 */
export const ticketsApi = (params: { hotelId?: number; status?: number; page?: number; size?: number }) =>
  request.get<any, PageResult<ServiceTicketVO>>('/chat/tickets', { params })

/** 工单状态处理：待处理→处理中/已完成；处理中→已完成；传 2 时必须带 handleResult */
export const ticketStatusApi = (id: number, data: { status: number; handleResult?: string }) =>
  request.put<any, ServiceTicketVO>('/chat/tickets/' + id + '/status', data)

// ==================== 管理端客服工作台 ====================

export const adminSessionsApi = (params: {
  status?: number
  transferFlag?: number
  rating?: number
  startDate?: string
  endDate?: string
  page: number
  size: number
}) => request.get<any, PageResult<ChatAdminSession>>('/chat/admin/sessions', { params })

export const adminSessionDetailApi = (sessionId: number) =>
  request.get<any, ChatSessionDetail>(`/chat/admin/sessions/${sessionId}`)

export const adminChatReplyApi = (sessionId: number, content: string) =>
  request.post<any, void>(`/chat/admin/sessions/${sessionId}/reply`, { content })

export const adminSessionTagApi = (sessionId: number, tag: string) =>
  request.put<any, void>(`/chat/admin/sessions/${sessionId}/tag`, { tag })

// ==================== 数据看板 ====================

export const chatTrendApi = (days = 7) =>
  request.get<any, TrendPoint[]>('/chat/admin/analytics/trend', { params: { days } })

export const chatResolutionApi = () =>
  request.get<any, ResolutionStats>('/chat/admin/analytics/resolution')

export const chatHotQuestionsApi = (limit = 10) =>
  request.get<any, HotQuestionVO[]>('/chat/admin/analytics/hot-questions', { params: { limit } })
