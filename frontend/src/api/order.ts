import request from './request'
import type { PageResult, OrderDetailVO, OrderVO } from '@/types'

export const myOrdersApi = (params: { status?: number; page?: number; size?: number }) =>
  request.get<any, PageResult<OrderVO>>('/orders', { params })

/** 订单详情（仅本人订单可访问） */
export const orderDetailApi = (orderNo: string) =>
  request.get<any, OrderDetailVO>(`/orders/${orderNo}`)

export const createOrderApi = (data: {
  hotelId: number
  roomTypeId: number
  checkinDate: string
  checkoutDate: string
  guestName: string
  guestPhone: string
  /** 用户指定房间ID（可选，不传则由系统自动分配） */
  roomId?: number
}) => request.post<any, OrderVO>('/orders/create', data)

export const cancelOrderApi = (orderNo: string) =>
  request.post<any, OrderVO>(`/orders/${orderNo}/cancel`)

export const payOrderApi = (orderNo: string) =>
  request.post<any, OrderVO>(`/orders/${orderNo}/pay`)
