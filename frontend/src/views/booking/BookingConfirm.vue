<template>
  <div class="booking">
    <el-card>
      <template #header>确认订单</template>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 480px">
        <el-form-item label="入住日期" prop="dates">
          <el-date-picker
            v-model="dates"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="入住日期"
            end-placeholder="离店日期"
            :disabled-date="disablePast"
            style="width: 100%"
            @change="onDatesChange"
          />
        </el-form-item>
        <el-form-item v-if="nightCount > 0" label="住宿晚数">
          {{ nightCount }} 晚
        </el-form-item>
        <el-form-item v-if="roomsLoaded" v-loading="roomsLoading" label="选择房间">
          <el-radio-group v-model="selectedRoomId" class="room-group">
            <el-radio-button :value="0">随机分配</el-radio-button>
            <el-radio-button v-for="r in availableRooms" :key="r.id" :value="r.id">
              {{ r.roomNo }} 号
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="roomsLoaded && availableRooms.length === 0" label="选择房间">
          <span class="no-room">{{ roomsError ? roomsError.message : '所选日期该房型暂无可选房间，请调整日期' }}</span>
        </el-form-item>
        <el-form-item label="入住人" prop="guestName">
          <el-input v-model="form.guestName" placeholder="请输入入住人姓名" clearable />
        </el-form-item>
        <el-form-item label="联系电话" prop="guestPhone">
          <el-input v-model="form.guestPhone" placeholder="请输入手机号" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交订单</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="order" class="result-card">
      <template #header>
        <el-tag type="success" size="large">下单成功</el-tag>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="订单状态">待支付</el-descriptions-item>
        <el-descriptions-item label="酒店">{{ order.hotelName }}</el-descriptions-item>
        <el-descriptions-item label="房型">{{ order.roomTypeName }}（{{ order.roomNo }}）</el-descriptions-item>
        <el-descriptions-item label="入住-离店">{{ order.checkinDate }} ~ {{ order.checkoutDate }}</el-descriptions-item>
        <el-descriptions-item label="晚数">{{ order.nightCount }}晚</el-descriptions-item>
        <el-descriptions-item label="订单金额">￥{{ order.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="支付时限">{{ order.expireTime }} 前</el-descriptions-item>
      </el-descriptions>
      <div class="actions">
        <el-button type="primary" :loading="paying" @click="onPay">立即支付</el-button>
        <el-button @click="$router.push('/orders')">稍后支付</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createOrderApi, payOrderApi } from '@/api/order'
import { availableRoomsApi } from '@/api/search'
import { useRequest } from '@/composables/useRequest'
import type { OrderVO, RoomVO } from '@/types'

const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const paying = ref(false)
const order = ref<OrderVO | null>(null)

const form = reactive({
  hotelId: Number(route.query.hotelId),
  roomTypeId: Number(route.query.roomTypeId),
  checkinDate: (route.query.checkin as string) || '',
  checkoutDate: (route.query.checkout as string) || '',
  guestName: '',
  guestPhone: ''
})

/** 入住/离店日期（可在确认页直接修改） */
const dates = ref<[string, string] | null>(
  form.checkinDate && form.checkoutDate ? [form.checkinDate, form.checkoutDate] : null
)

const nightCount = computed(() => {
  if (!dates.value?.[0] || !dates.value?.[1]) return 0
  const d1 = new Date(dates.value[0]).getTime()
  const d2 = new Date(dates.value[1]).getTime()
  const diff = Math.round((d2 - d1) / 86400000)
  return diff > 0 ? diff : 0
})

/** 日期修改后同步到表单（价格与库存以下单时后端核算为准） */
const onDatesChange = (val: [string, string] | null) => {
  if (val && val.length === 2) {
    form.checkinDate = val[0]
    form.checkoutDate = val[1]
  } else {
    form.checkinDate = ''
    form.checkoutDate = ''
  }
  loadRooms()
}

/** 禁止选择今天之前的日期 */
const disablePast = (date: Date) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

// ---------- 挑选房间 ----------
/** 0 = 随机分配（系统自动挑房）；>0 = 用户指定房间ID */
const selectedRoomId = ref(0)
const roomsLoaded = ref(false)
/** 可选房间：loading / data / error 统一交给 useRequest 管理 */
const {
  loading: roomsLoading,
  data: roomData,
  error: roomsError,
  run: runLoadRooms
} = useRequest(() => availableRoomsApi(form.hotelId, form.roomTypeId, form.checkinDate, form.checkoutDate))
const availableRooms = computed<RoomVO[]>(() => roomData.value ?? [])

const loadRooms = async () => {
  if (!form.hotelId || !form.roomTypeId || !form.checkinDate || !form.checkoutDate) {
    roomData.value = null
    roomsLoaded.value = false
    return
  }
  await runLoadRooms()
  selectedRoomId.value = 0
  roomsLoaded.value = true
}

const rules: FormRules = {
  guestName: [{ required: true, message: '请输入入住人姓名', trigger: 'blur' }],
  guestPhone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

const onSubmit = async () => {
  if (!dates.value?.[0] || !dates.value?.[1]) {
    ElMessage.warning('请选择入住和离店日期')
    return
  }
  if (nightCount.value <= 0) {
    ElMessage.warning('离店日期必须晚于入住日期')
    return
  }
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    order.value = await createOrderApi({
      ...form,
      roomId: selectedRoomId.value > 0 ? selectedRoomId.value : undefined
    })
    ElMessage.success('订单创建成功')
  } catch (e) {
    ElMessage.error((e as Error).message || '订单创建失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadRooms)

const onPay = async () => {
  if (!order.value) return
  paying.value = true
  try {
    order.value = await payOrderApi(order.value.orderNo)
    ElMessage.success('支付成功')
    router.push('/orders')
  } catch (e) {
    ElMessage.error((e as Error).message || '支付失败，请稍后重试')
  } finally {
    paying.value = false
  }
}
</script>

<style scoped>
.booking {
  max-width: 720px;
  margin: 0 auto;
}
.result-card {
  margin-top: 16px;
}
.actions {
  margin-top: 16px;
}
.room-group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.no-room {
  color: #f56c6c;
  font-size: 13px;
}
</style>
