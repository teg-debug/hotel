import request from './request'
import type { UserInfo } from '@/types'

/** 当前登录用户信息（含明文密码，演示用） */
export const meApi = () => request.get<any, UserInfo>('/user/me')

/** 修改本人密码 */
export const changePasswordApi = (data: { oldPassword: string; newPassword: string }) =>
  request.put<any, void>('/user/password', data)
