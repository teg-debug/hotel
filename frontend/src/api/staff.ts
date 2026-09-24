import request from './request'
import type { HotelVO, RoomVO } from '@/types'

/** 前台绑定的酒店信息 */
export const staffHotelApi = () => request.get<any, HotelVO>('/staff/hotel')

/** 绑定酒店的房间列表 */
export const staffRoomsApi = () => request.get<any, RoomVO[]>('/staff/rooms')

/** 修改房间物理状态：0-空闲 / 1-停用维修 / 2-打扫中（是否在住由 occupied 推导，不在此接口设置） */
export const staffRoomStatusApi = (roomId: number, status: number) =>
  request.put<any, RoomVO>(`/staff/rooms/${roomId}/status`, { status })
