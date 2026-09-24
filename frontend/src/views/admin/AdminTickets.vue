<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">工单处理</span>
        <div>
          <el-select v-model="query.hotelId" placeholder="全部酒店" clearable style="width: 200px" @change="load">
            <el-option v-for="h in hotels" :key="h.id" :label="h.name" :value="h.id" />
          </el-select>
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px; margin-left: 12px" @change="load">
            <el-option v-for="(item, key) in TICKET_STATUS" :key="key" :label="item.text" :value="Number(key)" />
          </el-select>
          <el-button style="margin-left: 12px" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="ticketNo" label="工单编号" width="170" />
      <el-table-column label="酒店" min-width="130">
        <template #default="{ row }">{{ row.hotelId ? hotelName(row.hotelId) : '-' }}</template>
      </el-table-column>
      <el-table-column label="房间" width="100">
        <template #default="{ row }">{{ row.roomId ? `房间 #${row.roomId}` : '-' }}</template>
      </el-table-column>
      <el-table-column prop="requestType" label="服务类型" width="110" />
      <el-table-column prop="content" label="需求描述" min-width="180" show-overflow-tooltip />
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          <el-tag :type="TICKET_PRIORITY[row.priority]?.type">{{ TICKET_PRIORITY[row.priority]?.text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="TICKET_STATUS[row.status]?.type">{{ TICKET_STATUS[row.status]?.text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="处理结果" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.handleResult || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button
              size="small"
              type="primary"
              :loading="statusSavingId === row.id"
              @click="start(row)"
            >
              开始处理
            </el-button>
            <el-button size="small" type="success" @click="openComplete(row)">标记完成</el-button>
          </template>
          <template v-else-if="row.status === 1">
            <el-button size="small" type="success" @click="openComplete(row)">标记完成</el-button>
          </template>
          <span v-else class="ended">已结束</span>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !list.length" description="暂无工单" />

    <div class="pager-wrap">
      <el-pagination
        v-if="total > 0"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size"
        v-model:current-page="query.page"
        @current-change="load"
      />
    </div>

    <!-- ==================== 标记完成 ==================== -->
    <el-dialog v-model="completeVisible" title="标记完成" width="520px">
      <el-form ref="completeFormRef" :model="completeForm" :rules="completeRules" label-width="90px">
        <el-form-item label="工单编号">{{ currentTicket?.ticketNo }}</el-form-item>
        <el-form-item label="处理结果" prop="handleResult">
          <el-input
            v-model="completeForm.handleResult"
            type="textarea"
            :rows="4"
            placeholder="请填写处理过程与结果（必填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeVisible = false">取消</el-button>
        <el-button type="primary" :loading="completing" @click="onComplete">提交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { adminHotelsApi } from '@/api/admin'
import { ticketStatusApi, ticketsApi } from '@/api/chat'
import { TICKET_PRIORITY, TICKET_STATUS, type HotelVO, type ServiceTicketVO } from '@/types'

const loading = ref(false)
const list = ref<ServiceTicketVO[]>([])
const total = ref(0)
const hotels = ref<HotelVO[]>([])
const query = reactive<{ hotelId?: number; status?: number; page: number; size: number }>({
  page: 1,
  size: 10
})
/** 正在处理的工单ID：请求期间只让该行按钮转圈 */
const statusSavingId = ref<number | null>(null)

const hotelName = (hotelId?: number) =>
  hotels.value.find((h) => h.id === hotelId)?.name || `酒店 #${hotelId}`

const load = async () => {
  loading.value = true
  try {
    const data = await ticketsApi(query)
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error((e as Error).message || '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 待处理 → 处理中 */
const start = async (row: ServiceTicketVO) => {
  try {
    await ElMessageBox.confirm(`确认开始处理工单 ${row.ticketNo}？`, '开始处理', { type: 'info' })
  } catch {
    return
  }
  statusSavingId.value = row.id
  try {
    await ticketStatusApi(row.id, { status: 1 })
    ElMessage.success('工单已进入处理中')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '工单状态更新失败')
  } finally {
    statusSavingId.value = null
  }
}

// ---------- 标记完成（状态传 2 时必须填处理结果） ----------
const completeVisible = ref(false)
const completing = ref(false)
const completeFormRef = ref<FormInstance>()
const currentTicket = ref<ServiceTicketVO | null>(null)
const completeForm = reactive({ handleResult: '' })

const completeRules: FormRules = {
  handleResult: [{ required: true, message: '请填写处理结果', trigger: 'blur' }]
}

const openComplete = (row: ServiceTicketVO) => {
  currentTicket.value = row
  completeForm.handleResult = ''
  completeVisible.value = true
}

const onComplete = async () => {
  if (!completeFormRef.value || !currentTicket.value) return
  try {
    await completeFormRef.value.validate()
  } catch {
    return
  }
  completing.value = true
  try {
    await ticketStatusApi(currentTicket.value.id, {
      status: 2,
      handleResult: completeForm.handleResult
    })
    ElMessage.success('工单已完成')
    completeVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '工单完成失败')
  } finally {
    completing.value = false
  }
}

onMounted(async () => {
  try {
    hotels.value = await adminHotelsApi()
  } catch (e) {
    ElMessage.error((e as Error).message || '酒店列表加载失败')
  }
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
.ended {
  color: #909399;
  font-size: 13px;
}
</style>
