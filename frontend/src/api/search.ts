import request from './request'
import type { PageResult, HotelSearchVO, RoomVO } from '@/types'

export interface SearchParams {
  city?: string
  starLevel?: number
  keyword?: string
  checkin?: string
  checkout?: string
  page?: number
  size?: number
}

export const searchHotelsApi = (params: SearchParams) =>
  request.get<any, PageResult<HotelSearchVO>>('/hotels/search', { params })

/** 指定酒店/房型在日期区间内的可用房间列表（下单前挑选房间） */
export const availableRoomsApi = (
  hotelId: number,
  roomTypeId: number,
  checkin: string,
  checkout: string
) =>
  request.get<any, RoomVO[]>(
    `/hotels/${hotelId}/room-types/${roomTypeId}/available-rooms`,
    { params: { checkin, checkout } }
  )
