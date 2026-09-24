<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">入住率统计</span>
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          placeholder="选择月份"
          :clearable="false"
          @change="load"
        />
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="hotelName" label="酒店" min-width="140" />
      <el-table-column prop="totalRooms" label="房间数" width="100" />
      <el-table-column prop="capacityNights" label="可售间夜" width="110" />
      <el-table-column prop="occupiedNights" label="已售间夜" width="110" />
      <el-table-column prop="orderCount" label="有效订单" width="100" />
      <el-table-column label="入住率" min-width="220">
        <template #default="{ row }">
          <el-progress :percentage="row.occupancyRate" :stroke-width="14" />
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminOccupancyApi } from '@/api/admin'
import type { StatsVO } from '@/types'

const loading = ref(false)
const list = ref<StatsVO[]>([])
const now = new Date()
const month = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`)

const load = async () => {
  loading.value = true
  try {
    list.value = await adminOccupancyApi(month.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
</style>
