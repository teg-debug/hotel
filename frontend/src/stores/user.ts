import { defineStore } from 'pinia'
import type { LoginVO, UserInfo } from '@/types'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null') as UserInfo | null
  }),
  getters: {
    isLogin: (state) => !!state.token,
    /** 经营者(1)或管理员(2)可进入管理端（前台3不可进） */
    isAdmin: (state) => state.userInfo?.role === 1 || state.userInfo?.role === 2,
    /** 系统管理员（2） */
    isSysAdmin: (state) => state.userInfo?.role === 2,
    /** 酒店前台（3） */
    isStaff: (state) => state.userInfo?.role === 3
  },
  actions: {
    setLogin(data: LoginVO) {
      this.token = data.token
      this.userInfo = data.userInfo
      localStorage.setItem('token', data.token)
      localStorage.setItem('userInfo', JSON.stringify(data.userInfo))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    }
  }
})
