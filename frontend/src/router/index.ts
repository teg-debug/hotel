import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/auth/Login.vue'), meta: { public: true } },
    { path: '/register', component: () => import('@/views/auth/Register.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/components/layout/UserLayout.vue'),
      children: [
        { path: '', component: () => import('@/views/home/Home.vue') },
        { path: 'search', component: () => import('@/views/search/SearchResult.vue') },
        { path: 'booking', component: () => import('@/views/booking/BookingConfirm.vue') },
        { path: 'orders', component: () => import('@/views/order/MyOrder.vue') },
        { path: 'orders/:orderNo', component: () => import('@/views/order/OrderDetail.vue') },
        { path: 'account', component: () => import('@/views/account/Account.vue') }
      ]
    },
    {
      path: '/admin',
      component: () => import('@/components/layout/AdminLayout.vue'),
      meta: { admin: true },
      children: [
        { path: '', redirect: '/admin/hotels' },
        { path: 'hotels', component: () => import('@/views/admin/AdminHotels.vue') },
        { path: 'orders', component: () => import('@/views/admin/AdminOrders.vue') },
        { path: 'stats', component: () => import('@/views/admin/AdminStats.vue') },
        { path: 'users', component: () => import('@/views/admin/AdminUsers.vue') },
        { path: 'chat/dashboard', component: () => import('@/views/admin/ChatDashboard.vue') },
        { path: 'chat/analytics', component: () => import('@/views/admin/ChatAnalytics.vue') },
        { path: 'chat/tickets', component: () => import('@/views/admin/AdminTickets.vue') },
        { path: 'knowledge', component: () => import('@/views/admin/AdminKnowledge.vue') }
      ]
    },
    {
      path: '/staff',
      component: () => import('@/components/layout/UserLayout.vue'),
      meta: { staff: true },
      children: [{ path: '', component: () => import('@/views/staff/StaffRooms.vue') }]
    }
  ]
})

// 全局前置守卫：未登录跳登录；管理端要求 经营者/管理员；前台工作台要求 前台
router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.public) return true
  if (!store.isLogin) return '/login'
  if (to.meta.admin && !store.isAdmin) return '/'
  if (to.meta.staff && !store.isStaff) return '/'
  return true
})

export default router
