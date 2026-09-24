<template>
  <div class="order-detail">
    <div class="back-bar">
      <el-button link @click="$router.push('/orders')">返回订单列表</el-button>
    </div>

    <el-card v-if="error" class="state-card">
      <el-empty :description="error.message || '订单详情加载失败'">
        <el-button type="primary" @click="run">重新加载</el-button>
      </el-empty>
    </el-card>

    <template v-else-if="detail">
      <el-card v-loading="loading">
        <template #header>
          <span class="card-title">订单详情</span>
        </template>

        <div class="group">
          <div class="group-title">订单信息</div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="ORDER_STATUS[detail.status]?.type">{{ ORDER_STATUS[detail.status]?.text }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="下单时间">{{ detail.createTime }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="group">
          <div class="group-title">入住信息</div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="酒店">{{ detail.hotelName }}</el-descriptions-item>
            <el-descriptions-item label="房型">{{ detail.roomTypeName }}</el-descriptions-item>
            <el-descriptions-item label="房间号">{{ detail.roomNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="入住日期">{{ detail.checkinDate }}</el-descriptions-item>
            <el-descriptions-item label="离店日期">{{ detail.checkoutDate }}</el-descriptions-item>
            <el-descriptions-item label="入住晚数">{{ detail.nightCount }} 晚</el-descriptions-item>
            <el-descriptions-item label="入住人">{{ detail.guestName }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.guestPhone }}</el-descriptions-item>
            <el-descriptions-item label="备注">{{ detail.remark || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="group">
          <div class="group-title">费用信息</div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="房单价">￥{{ detail.roomPrice }}</el-descriptions-item>
            <el-descriptions-item label="会员折扣">{{ discountText }}</el-descriptions-item>
            <el-descriptions-item label="订单总额">
              <span class="amount">￥{{ detail.totalAmount }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 待支付：支付时限提示 + 立即支付 -->
        <div v-if="detail.status === 0" class="pay-tip">
          <span class="pay-tip-text">请在 {{ detail.expireTime }} 前完成支付，超时订单将自动取消</span>
          <el-button type="primary" :loading="paying" @click="onPay">立即支付</el-button>
        </div>

        <!-- 操作区：仅待支付/已确认可取消；已入住提示到前台办理 -->
        <div class="actions">
          <el-button
            v-if="detail.status === 0 || detail.status === 1"
            :loading="cancelling"
            @click="onCancel"
          >
            {{ detail.status === 1 ? '取消并退款' : '取消订单' }}
          </el-button>
          <el-alert
            v-if="detail.status === 2"
            type="info"
            :closable="false"
            title="已入住，请到前台办理退房"
          />
        </div>

        <div class="group">
          <div class="group-title">支付流水</div>
          <el-table v-if="payments.length" :data="payments">
            <el-table-column prop="payNo" label="流水号" min-width="190" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="PAYMENT_BIZ_TYPE[row.bizType]?.type">{{ PAYMENT_BIZ_TYPE[row.bizType]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="支付方式" width="110">
              <template #default="{ row }">{{ PAY_TYPE_TEXT[row.payType] || '-' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="110">
              <template #default="{ row }">￥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="PAYMENT_STATUS[row.status]?.type">{{ PAYMENT_STATUS[row.status]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="时间" width="180" />
          </el-table>
          <el-empty v-else description="暂无支付流水" />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrderApi, orderDetailApi, payOrderApi } from '@/api/order'
import { useRequest } from '@/composables/useRequest'
import { ORDER_STATUS, PAYMENT_BIZ_TYPE, PAYMENT_STATUS, PAY_TYPE_TEXT } from '@/types'

const route = useRoute()
const orderNo = route.params.orderNo as string

/** 订单详情：loading / data / error 统一交给 useRequest 管理 */
const { loading, data: detail, error, run } = useRequest(() => orderDetailApi(orderNo))

const payments = computed(() => detail.value?.payments || [])

/** 会员折扣文案：0.95 → 9.5 折；1.00 → 无折扣 */
const discountText = computed(() => {
  const discount = detail.value?.memberDiscount
  if (!discount || discount >= 1) return '无折扣'
  return `${Math.round(discount * 100) / 10} 折`
})

const paying = ref(false)
const cancelling = ref(false)

const onPay = async () => {
  paying.value = true
  try {
    await payOrderApi(orderNo)
    ElMessage.success('支付成功')
    run()
  } catch (e) {
    ElMessage.error((e as Error).message || '支付失败，请稍后重试')
  } finally {
    paying.value = false
  }
}

const onCancel = async () => {
  // 已确认订单取消时后端会先退款，需要向用户说明按原支付渠道退回
  const refund = detail.value?.status === 1
  try {
    await ElMessageBox.confirm(
      refund
        ? '该订单已确认，取消将发起退款（款项按原支付渠道退回），确定取消吗？'
        : '确定取消该订单吗？取消后房间将被释放。',
      refund ? '取消并退款' : '取消订单',
      { type: 'warning' }
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    await cancelOrderApi(orderNo)
    ElMessage.success(refund ? '订单已取消，退款将原路退回' : '订单已取消')
    run()
  } catch (e) {
    ElMessage.error((e as Error).message || '订单取消失败')
  } finally {
    cancelling.value = false
  }
}

onMounted(run)
</script>

<style scoped>
.order-detail {
  max-width: 960px;
  margin: 0 auto;
}
.back-bar {
  margin-bottom: 12px;
}
.state-card {
  text-align: center;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
}
.group {
  margin-bottom: 18px;
}
.group-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
}
.amount {
  font-weight: 700;
  color: #f56c6c;
}
.pay-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: #fdf6ec;
  border-radius: 4px;
}
.pay-tip-text {
  color: #e6a23c;
  font-size: 13px;
}
.actions {
  margin-bottom: 18px;
}
</style>
