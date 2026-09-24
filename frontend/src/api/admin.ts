import request from './request'
import type {
  PageResult,
  OrderVO,
  HotelVO,
  StatsVO,
  OperatorVO,
  OperatorCreateForm,
  FrontDeskForm,
  HotelSaveForm,
  RoomTypeVO,
  RoomTypeSaveForm,
  RoomVO,
  BatchRoomForm,
  UserInfo
} from '@/types'

export const adminHotelsApi = () => request.get<any, HotelVO[]>('/admin/hotels')

/** 经营者列表（系统管理员添加酒店时选择用） */
export const adminOperatorsApi = () => request.get<any, OperatorVO[]>('/admin/operators')

/** 系统管理员新增经营者账号（role=1） */
export const adminCreateOperatorApi = (data: OperatorCreateForm) =>
  request.post<any, OperatorVO>('/admin/operators', data)

/** 某酒店的前台账号列表 */
export const hotelStaffApi = (hotelId: number) =>
  request.get<any, OperatorVO[]>(`/admin/hotels/${hotelId}/staff`)

/** 为某酒店新增前台账号 */
export const createHotelStaffApi = (hotelId: number, data: FrontDeskForm) =>
  request.post<any, OperatorVO>(`/admin/hotels/${hotelId}/staff`, data)

/** 重置某酒店前台账号密码（经营者/管理员） */
export const resetStaffPasswordApi = (hotelId: number, staffId: number, newPassword: string) =>
  request.put<any, void>(`/admin/hotels/${hotelId}/staff/${staffId}/password`, { newPassword })

/** 系统管理员分页查看所有用户 */
export const adminUsersApi = (params: { page?: number; size?: number }) =>
  request.get<any, PageResult<UserInfo>>('/admin/users', { params })

/** 系统管理员重置任意用户密码 */
export const adminResetPasswordApi = (userId: number, newPassword: string) =>
  request.put<any, void>(`/admin/users/${userId}/password`, { newPassword })

/** 系统管理员新增酒店 */
export const adminCreateHotelApi = (data: HotelSaveForm) =>
  request.post<any, HotelVO>('/admin/hotels', data)

/** 修改酒店信息（经营者改自己名下 / 管理员改全部） */
export const adminUpdateHotelApi = (id: number, data: HotelSaveForm) =>
  request.put<any, HotelVO>(`/admin/hotels/${id}`, data)

// ---------------- 房型管理 ----------------

export const roomTypesApi = (hotelId: number) =>
  request.get<any, RoomTypeVO[]>(`/admin/hotels/${hotelId}/room-types`)

export const createRoomTypeApi = (data: RoomTypeSaveForm) =>
  request.post<any, RoomTypeVO>('/admin/room-types', data)

export const updateRoomTypeApi = (id: number, data: RoomTypeSaveForm) =>
  request.put<any, RoomTypeVO>(`/admin/room-types/${id}`, data)

export const deleteRoomTypeApi = (id: number) =>
  request.delete<any, void>(`/admin/room-types/${id}`)

// ---------------- 房间管理 ----------------

export const roomsApi = (roomTypeId: number) =>
  request.get<any, RoomVO[]>(`/admin/room-types/${roomTypeId}/rooms`)

export const batchCreateRoomsApi = (roomTypeId: number, data: BatchRoomForm) =>
  request.post<any, RoomVO[]>(`/admin/room-types/${roomTypeId}/rooms/batch`, data)

export const updateRoomStatusApi = (roomId: number, status: number) =>
  request.put<any, RoomVO>(`/admin/rooms/${roomId}/status`, { status })

export const deleteRoomApi = (roomId: number) =>
  request.delete<any, void>(`/admin/rooms/${roomId}`)

export const adminOrdersApi = (params: {
  hotelId?: number
  status?: number
  page?: number
  size?: number
}) => request.get<any, PageResult<OrderVO>>('/admin/orders', { params })

/** 入住登记：订单 1-已确认 → 2-已入住 */
export const adminCheckinApi = (orderNo: string) =>
  request.put<any, OrderVO>(`/admin/orders/${orderNo}/checkin`)

/** 退房：订单 2-已入住 → 4-已完成 + 房间置为打扫中 */
export const adminCheckoutApi = (orderNo: string) =>
  request.put<any, OrderVO>(`/admin/orders/${orderNo}/checkout`)

export const adminOccupancyApi = (month: string) =>
  request.get<any, StatsVO[]>('/admin/stats/occupancy', { params: { month } })
