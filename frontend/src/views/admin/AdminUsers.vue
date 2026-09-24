<template>
  <el-card>
    <template #header>
      <span class="header-title">用户管理</span>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="ROLE_TAG[row.role] || 'info'">{{ ROLE_MAP[row.role] || row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="绑定酒店" width="90">
        <template #default="{ row }">{{ row.hotelId || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="warning" @click="resetPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-wrap">
      <el-pagination
        v-if="total > 0"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        v-model:current-page="page"
        @current-change="load"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminResetPasswordApi, adminUsersApi } from '@/api/admin'
import { ROLE_MAP, type UserInfo } from '@/types'

const ROLE_TAG: Record<number, 'success' | 'warning' | 'danger' | 'primary' | 'info'> = {
  0: 'info',
  1: 'warning',
  2: 'danger',
  3: 'primary'
}

const loading = ref(false)
const list = ref<UserInfo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const load = async () => {
  loading.value = true
  try {
    const data = await adminUsersApi({ page: page.value, size: size.value })
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error((e as Error).message || '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

const resetPwd = async (row: UserInfo) => {
  const { value } = await ElMessageBox.prompt(`为用户「${row.username}」设置新密码`, '重置密码', {
    inputType: 'password',
    inputPattern: /^.{6,20}$/,
    inputErrorMessage: '密码长度为6-20位'
  }).catch(() => ({ value: '' }))
  if (!value) return
  try {
    await adminResetPasswordApi(row.id, value)
    ElMessage.success('密码已重置')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '密码重置失败')
  }
}

onMounted(load)
</script>

<style scoped>
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.pwd {
  font-weight: 600;
  color: #f56c6c;
  letter-spacing: 1px;
}
.pager-wrap {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
