import request from './request'
import type { LoginVO } from '@/types'

export const loginApi = (data: { username: string; password: string }) =>
  request.post<any, LoginVO>('/auth/login', data)

export const registerApi = (data: {
  username: string
  password: string
  nickname?: string
  phone?: string
}) => request.post<any, void>('/auth/register', data)

export const logoutApi = () => request.post<any, void>('/auth/logout')
