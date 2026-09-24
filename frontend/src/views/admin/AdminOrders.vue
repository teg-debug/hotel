<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">订单管理</span>
        <div>
          <el-select v-model="query.hotelId" placeholder="全部酒店" clearable style="width: 200px" @change="load">
            <el-option v-for="h in hotels" :key="h.id" :label="h.name" :value="h.id" />
          </el-select>
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px; margin-left: 12px" @change="load">
            <el-option v-for="(item, key) in ORDER_STATUS" :key="key" :label="item.text" :value="Number(key)" />
          </el-select>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="orderNo" label="订单号" width="190" />
      <el-table-column prop="hotelName" label="酒店" min-width="130" />
      <el-table-column prop="roomTypeName" label="房型" width="100" />
      <el-table-column label="房间号" width="90">
        <template #default="{ row }">{{ row.roomNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="guestName" label="入住人" width="90" />
      <el-table-column label="入住-离店" width="190">
        <template #default="{ row }">{{ row.checkinDate }} ~ {{ row.checkoutDate }}</template>
      </el-table-column>
      <el-table-column label="金额" width="110">
        <template #default="{ row }">￥{{ row.totalAmount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="ORDER_STATUS[row.status]?.type">{{ ORDER_STATUS[row.status]?.text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" width="170">
        <template #default="{ row }">{{ row.createTime }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <template v-if="row.status === 1">
            <el-button size="small" type="primary" @click="checkin(row)">办理入住</el-button>
          </template>
          <template v-else-if="row.status === 2">
            <el-button size="small" type="warning" @click="checkout(row)">办理退房</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-wrap">
      <el-pagination
        v-if="total > 0"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size ?? 10"
        v-model:current-page="query.page"
        @current-change="load"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminCheckinApi,
  adminCheckoutApi,
  adminHotelsApi,
  adminOrdersApi
} from '@/api/admin'
import { ORDER_STATUS, type HotelVO, type OrderVO } from '@/types'

const loading = ref(false)
const list = ref<OrderVO[]>([])
const total = ref(0)
const hotels = ref<HotelVO[]>([])
const query = reactive<{ hotelId?: number; status?: number; page: number; size: number }>({
  page: 1,
  size: 10
})

const load = async () => {
  loading.value = true
  try {
    const data = await adminOrdersApi(query)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const checkin = async (row: OrderVO) => {
  try {
    await ElMessageBox.confirm(`确认为订单 ${row.orderNo} 办理入住？`, '办理入住', { type: 'info' })
  } catch {
    return
  }
  await adminCheckinApi(row.orderNo)
  ElMessage.success('已办理入住')
  load()
}

const checkout = async (row: OrderVO) => {
  try {
    await ElMessageBox.confirm(
      `确认为订单 ${row.orderNo} 办理退房？退房后房间将置为「打扫中」，需重新标记为空闲才可再订。`,
      '办理退房',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await adminCheckoutApi(row.orderNo)
  ElMessage.success('已办理退房')
  load()
}

onMounted(async () => {
  hotels.value = await adminHotelsApi()
  load()
})
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
.pager-wrap {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
