<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">我的订单</span>
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 150px" @change="load">
          <el-option v-for="(item, key) in ORDER_STATUS" :key="key" :label="item.text" :value="Number(key)" />
        </el-select>
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column label="订单号" width="190">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/orders/${row.orderNo}`)">{{ row.orderNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="hotelName" label="酒店" min-width="140" />
      <el-table-column prop="roomTypeName" label="房型" width="100" />
      <el-table-column label="房间号" width="90">
        <template #default="{ row }">{{ row.roomNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="入住-离店" width="200">
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
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 0"
            size="small"
            type="primary"
            :loading="payingNo === row.orderNo"
            @click="pay(row)"
          >
            支付
          </el-button>
          <el-button
            v-if="row.status === 0 || row.status === 1 || row.status === 2"
            size="small"
            :loading="cancellingNo === row.orderNo"
            @click="cancel(row)"
          >
            取消
          </el-button>
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
import { cancelOrderApi, myOrdersApi, payOrderApi } from '@/api/order'
import { ORDER_STATUS, type OrderVO } from '@/types'

const loading = ref(false)
const list = ref<OrderVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const status = ref<number | undefined>(undefined)
/** 正在提交的订单号：用于按钮 loading，避免重复点击 */
const payingNo = ref('')
const cancellingNo = ref('')

const load = async () => {
  loading.value = true
  try {
    const data = await myOrdersApi({ status: status.value, page: page.value, size })
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error((e as Error).message || '订单列表加载失败')
  } finally {
    loading.value = false
  }
}

const pay = async (row: OrderVO) => {
  payingNo.value = row.orderNo
  try {
    await payOrderApi(row.orderNo)
    ElMessage.success('支付成功')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '支付失败，请稍后重试')
  } finally {
    payingNo.value = ''
  }
}

const cancel = async (row: OrderVO) => {
  // 已确认订单后端会先退款再取消；已入住订单只能到前台办理
  const confirmed = row.status === 1
  const tip = confirmed
    ? '该订单已确认，取消将发起退款（款项按原支付渠道退回），确定取消吗？'
    : row.status === 2
      ? '该订单已入住，取消需到酒店前台办理。确定继续吗？'
      : '确定取消该订单吗？取消后房间将被释放。'
  try {
    await ElMessageBox.confirm(tip, confirmed ? '取消并退款' : '提示', { type: 'warning' })
  } catch {
    return
  }
  cancellingNo.value = row.orderNo
  try {
    await cancelOrderApi(row.orderNo)
    ElMessage.success(confirmed ? '订单已取消，退款将原路退回' : '订单已取消')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '订单取消失败')
  } finally {
    cancellingNo.value = ''
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
.pager-wrap {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
