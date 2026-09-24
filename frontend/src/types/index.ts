/** 全局类型定义（与后端 VO 对齐） */

export interface PageResult<T> {
  total: number
  pages: number
  current: number
  size: number
  records: T[]
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  phone?: string
  email?: string
  avatar?: string
  memberLevel: number
  role: number
  hotelId?: number
}

/** 用户角色映射 */
export const ROLE_MAP: Record<number, string> = {
  0: '普通用户',
  1: '酒店经营者',
  2: '系统管理员',
  3: '酒店前台'
}

export interface LoginVO {
  token: string
  userInfo: UserInfo
}

export interface RoomTypeSearchVO {
  id: number
  name: string
  bedType?: string
  maxGuests: number
  breakfast: number
  price: number
  imgUrl?: string
  availableCount: number
}

export interface HotelSearchVO {
  hotelId: number
  hotelName: string
  city: string
  address: string
  starLevel: number
  description?: string
  coverImg?: string
  images?: string
  latitude?: number
  longitude?: number
  lowestPrice: number
  availableRoomTypes: RoomTypeSearchVO[]
}

export interface OrderVO {
  orderNo: string
  hotelId: number
  hotelName: string
  roomTypeId: number
  roomTypeName: string
  roomNo: string
  checkinDate: string
  checkoutDate: string
  nightCount: number
  guestName: string
  guestPhone: string
  roomPrice: number
  totalAmount: number
  status: number
  cancelReason?: string
  expireTime: string
  createTime: string
}

/** 支付流水项 */
export interface PaymentLogItem {
  payNo: string
  /** 业务类型：1-支付 2-退款 */
  bizType: number
  /** 支付方式：0-模拟支付 1-微信 2-支付宝 */
  payType: number
  amount: number
  /** 流水状态：0-处理中 1-成功 2-失败 */
  status: number
  createTime: string
}

/** 订单详情（含支付流水） */
export interface OrderDetailVO {
  orderNo: string
  hotelId: number
  hotelName: string
  roomTypeId: number
  roomTypeName: string
  roomId?: number
  roomNo?: string
  checkinDate: string
  checkoutDate: string
  nightCount: number
  guestName: string
  guestPhone: string
  remark?: string | null
  /** 已含会员折扣的成交单价 */
  roomPrice: number
  /** 会员折扣率：0.95 表示 95 折，1 表示无折扣 */
  memberDiscount: number
  totalAmount: number
  status: number
  cancelReason?: string | null
  payTime?: string | null
  expireTime?: string | null
  createTime: string
  updateTime?: string | null
  payments: PaymentLogItem[]
}

export interface HotelVO {
  id: number
  ownerId?: number
  name: string
  city: string
  address: string
  starLevel: number
  description?: string
  coverImg?: string
  images?: string
  latitude?: number
  longitude?: number
  phone?: string
  checkinTime?: string
  checkoutTime?: string
  status: number
}

/** 新增/编辑酒店表单 */
export interface HotelSaveForm {
  ownerId?: number
  name: string
  city: string
  address: string
  starLevel: number
  description?: string
  coverImg?: string
  images?: string
  latitude?: number
  longitude?: number
  phone?: string
  checkinTime: string
  checkoutTime: string
  /** 状态：0-下架 1-营业中；不传表示保持原状态 */
  status?: number
}

export interface OperatorVO {
  id: number
  username: string
  nickname: string
}

/** 新增经营者账号表单 */
export interface OperatorCreateForm {
  username: string
  password: string
  nickname?: string
  phone?: string
}

export interface RoomTypeVO {
  id: number
  hotelId: number
  name: string
  bedType?: string
  area?: number
  maxGuests: number
  price: number
  breakfast: number
  imgUrl?: string
  status: number
  roomCount?: number
}

/** 新增/编辑房型表单 */
export interface RoomTypeSaveForm {
  hotelId: number
  name: string
  bedType?: string
  area?: number
  maxGuests: number
  price: number
  breakfast: number
  status: number
}

export interface RoomVO {
  id: number
  hotelId: number
  roomTypeId: number
  roomTypeName?: string
  roomNo: string
  floor?: number
  /** 物理状态：0-空闲 1-停用维修 2-打扫中 */
  status: number
  /** 是否存在在住订单（由订单日期区间推导，不再编码进 status） */
  occupied?: boolean
}

/** 房间物理状态映射：与后端 Room.STATUS_* 保持一致 */
export const ROOM_STATUS: Record<number, { text: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  0: { text: '空闲', type: 'success' },
  1: { text: '停用维修', type: 'warning' },
  2: { text: '打扫中', type: 'info' }
}

/** 新增前台账号表单 */
export interface FrontDeskForm {
  username: string
  password: string
  nickname?: string
  phone?: string
}

/** 批量生成房间表单（房间号 = 楼层×100 + 编号） */
export interface BatchRoomForm {
  floor: number
  startNo: number
  endNo: number
}

export interface StatsVO {
  hotelId?: number
  hotelName: string
  totalRooms: number
  capacityNights: number
  occupiedNights: number
  occupancyRate: number
  orderCount: number
}

/** 订单状态映射 */
export const ORDER_STATUS: Record<number, { text: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }> = {
  0: { text: '待支付', type: 'warning' },
  1: { text: '已确认', type: 'success' },
  2: { text: '已入住', type: 'primary' },
  3: { text: '已取消', type: 'info' },
  4: { text: '已完成', type: 'success' }
}

/** 支付流水业务类型映射：1-支付 2-退款 */
export const PAYMENT_BIZ_TYPE: Record<number, { text: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }> = {
  1: { text: '支付', type: 'primary' },
  2: { text: '退款', type: 'warning' }
}

/** 支付流水状态映射：0-处理中 1-成功 2-失败 */
export const PAYMENT_STATUS: Record<number, { text: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }> = {
  0: { text: '处理中', type: 'warning' },
  1: { text: '成功', type: 'success' },
  2: { text: '失败', type: 'danger' }
}

/** 支付方式文案：0-模拟支付 1-微信 2-支付宝 */
export const PAY_TYPE_TEXT: Record<number, string> = {
  0: '模拟支付',
  1: '微信',
  2: '支付宝'
}

// ============ 智能客服模块 ============

/** 对话消息：sender 0-用户 1-AI 2-人工 */
export interface ChatMessage {
  id: number
  sessionId: number
  sender: number
  content: string
  msgType: number
  intentTag?: string
  toolName?: string
  createTime: string
}

/** 会话状态：0-进行中 1-已结束 2-已转人工 3-超时回收 */
export interface ChatSession {
  id: number
  sessionNo: string
  hotelId?: number
  source: number
  status: number
  transferFlag: number
  transferReason?: string
  messageCount: number
  rating?: number
  ratingComment?: string
  startTime: string
}

/** 聊天回复（REST 同步 / WebSocket 推送共用） */
export interface ChatReplyVO {
  sessionId: number
  reply: string
  intent?: string
  confidence?: number
  needTransfer: boolean
  transferReason?: string
  source: string
}

/** 知识库条目 */
export interface KnowledgeEntry {
  id: number
  hotelId?: number
  question: string
  answer: string
  category?: string
  similarityThreshold: number
  status: number
  hitCount: number
}

/** 服务工单（送水/送六小件/清洁提醒等） */
export interface ServiceTicketVO {
  id: number
  ticketNo: string
  sessionId?: number
  userId?: number
  hotelId?: number
  roomId?: number | null
  requestType: string
  content: string
  /** 优先级：1-普通 2-紧急 */
  priority: number
  /** 状态：0-待处理 1-处理中 2-已完成 3-已取消 */
  status: number
  assigneeId?: number | null
  handleResult?: string | null
  handledTime?: string | null
  createTime: string
}

/** 工单状态映射 */
export const TICKET_STATUS: Record<number, { text: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }> = {
  0: { text: '待处理', type: 'warning' },
  1: { text: '处理中', type: 'primary' },
  2: { text: '已完成', type: 'success' },
  3: { text: '已取消', type: 'info' }
}

/** 工单优先级映射：1-普通 2-紧急 */
export const TICKET_PRIORITY: Record<number, { text: string; type: 'primary' | 'success' | 'info' | 'warning' | 'danger' }> = {
  1: { text: '普通', type: 'info' },
  2: { text: '紧急', type: 'danger' }
}

/** 客服工作台 - 会话列表项 */
export interface ChatAdminSession {
  id: number
  sessionNo: string
  userId: number
  username: string
  memberLevel: number
  orderCount: number
  hotelId?: number
  source: number
  status: number
  transferFlag: number
  transferReason?: string
  tag?: string
  rating?: number
  messageCount: number
  startTime: string
  durationMin: number
  pending: boolean
}

/** 客服工作台 - 会话详情 */
export interface ChatSessionDetail {
  session: ChatAdminSession
  username: string
  memberLevel: number
  orderCount: number
  messages: ChatMessage[]
}

/** 数据看板 */
export interface TrendPoint {
  date: string
  count: number
}

export interface ResolutionStats {
  aiResolved: number
  transferred: number
  active: number
  total: number
  aiResolveRate: number
  transferRate: number
}

/** 热问榜项（口径：用户真实提问次数） */
export interface HotQuestionVO {
  /** 用户提问原文 */
  question: string
  /** 识别出的意图标签，可能为空 */
  category?: string
  /** 该问题被提问的次数 */
  askCount: number
  /** 该问题最终转人工的次数：数值高说明知识库没接住 */
  transferCount: number
}

/** 坐席通知（转人工 / 用户追问），由后端投递到 /user/queue/staff */
export interface StaffAlert {
  /** transfer-转人工 / 用户追问 */
  type: string
  sessionId: number
  hotelId?: number
  reason?: string
}

/** 会员等级文案 */
export const MEMBER_LEVEL_TEXT = ['普通会员', '银卡会员', '金卡会员']
