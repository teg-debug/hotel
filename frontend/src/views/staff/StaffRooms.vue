<template>
  <div class="staff-page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">{{ hotel?.name || '前台工作台' }}</span>
          <el-tag v-if="hotel" type="info">{{ hotel.city }} · {{ hotel.address }}</el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="仅可操作本酒店房间物理状态（空闲 / 停用维修 / 打扫中），不可新增房间与房型"
        style="margin-bottom: 12px"
      />

      <el-table v-loading="loading" :data="rooms">
        <el-table-column prop="roomNo" label="房间号" width="100" sortable />
        <el-table-column prop="roomTypeName" label="房型" min-width="120" />
        <el-table-column prop="floor" label="楼层" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="ROOM_STATUS[row.status]?.type">{{ ROOM_STATUS[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在住" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.occupied" type="danger" size="small">在住</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button
              size="small"
              type="success"
              :disabled="row.status === 0 || updatingId === row.id"
              @click="setStatus(row, 0)"
            >
              空闲
            </el-button>
            <el-button
              size="small"
              type="warning"
              :disabled="row.status === 1 || updatingId === row.id"
              @click="setStatus(row, 1)"
            >
              停用维修
            </el-button>
            <el-button
              size="small"
              type="info"
              :disabled="row.status === 2 || updatingId === row.id"
              @click="setStatus(row, 2)"
            >
              打扫中
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rooms.length" description="该酒店暂无房间" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { staffHotelApi, staffRoomsApi, staffRoomStatusApi } from '@/api/staff'
import { ROOM_STATUS, type HotelVO, type RoomVO } from '@/types'

const loading = ref(false)
const hotel = ref<HotelVO | null>(null)
const rooms = ref<RoomVO[]>([])
/** 正在切换状态的房间ID：请求期间禁用该行按钮，避免重复提交 */
const updatingId = ref<number | null>(null)

const load = async () => {
  loading.value = true
  try {
    hotel.value = await staffHotelApi()
    rooms.value = await staffRoomsApi()
  } catch (e) {
    ElMessage.error((e as Error).message || '房间列表加载失败')
  } finally {
    loading.value = false
  }
}

const setStatus = async (row: RoomVO, status: number) => {
  const label = ROOM_STATUS[status]?.text || ''
  try {
    await ElMessageBox.confirm(`将房间 ${row.roomNo} 标记为「${label}」？`, '确认操作', { type: 'warning' })
  } catch {
    return
  }
  updatingId.value = row.id
  try {
    const updated = await staffRoomStatusApi(row.id, status)
    // 用后端返回的最新 RoomVO 就地更新该行（含 occupied），避免整表重载
    const index = rooms.value.findIndex((r) => r.id === row.id)
    if (index > -1) rooms.value[index] = updated
    ElMessage.success(`已标记为「${label}」`)
  } catch (e) {
    ElMessage.error((e as Error).message || '房间状态修改失败')
  } finally {
    updatingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.staff-page {
  max-width: 960px;
  margin: 0 auto;
}
.header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.title {
  font-size: 16px;
  font-weight: 600;
}
</style>
