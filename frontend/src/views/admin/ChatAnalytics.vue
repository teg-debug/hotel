<template>
  <div class="chat-analytics">
    <!-- 图表区 -->
    <div class="charts">
      <el-card class="chart-card">
        <template #header>近 7 日对话量趋势</template>
        <div ref="trendRef" class="chart trend-chart" />
      </el-card>
      <el-card class="chart-card">
        <template #header>AI 解决 vs 转人工</template>
        <div ref="pieRef" class="chart pie-chart" />
      </el-card>
      <el-card class="chart-card">
        <template #header>高频问题 Top 10</template>
        <div ref="barRef" class="chart bar-chart" />
      </el-card>
    </div>

    <!-- 热问榜：口径为用户真实提问次数 -->
    <el-card class="table-card">
      <template #header>
        <span class="header-title">热问榜 Top 10（用户真实提问）</span>
      </template>
      <el-table :data="hotQuestions">
        <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
        <el-table-column label="意图" width="140">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column prop="askCount" label="提问次数" width="110" />
        <el-table-column prop="transferCount" label="转人工次数" width="120" />
      </el-table>
      <el-empty v-if="!hotQuestions.length" description="暂无提问记录" />
    </el-card>

    <!-- 历史会话表格 -->
    <el-card class="table-card">
      <template #header>
        <div class="header">
          <span class="header-title">历史会话</span>
          <div class="filters">
            <el-date-picker
              v-model="query.dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
              @change="onFilter"
            />
            <el-select v-model="query.rating" placeholder="满意度" clearable style="width: 120px" @change="onFilter">
              <el-option v-for="n in [5, 4, 3, 2, 1]" :key="n" :label="`${n} 星`" :value="n" />
            </el-select>
            <el-select v-model="query.transferFlag" placeholder="是否转人工" clearable style="width: 130px" @change="onFilter">
              <el-option label="已转人工" :value="1" />
              <el-option label="未转人工" :value="0" />
            </el-select>
          </div>
        </div>
      </template>

      <el-table v-loading="tableLoading" :data="list">
        <el-table-column prop="sessionNo" label="会话编号" width="150" />
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="会员等级" width="100">
          <template #default="{ row }">{{ MEMBER_LEVEL_TEXT[row.memberLevel] || '普通' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_MAP[row.status]?.type">{{ STATUS_MAP[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="是否转人工" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.transferFlag === 1" type="warning" size="small">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="满意度" width="100">
          <template #default="{ row }">
            <span v-if="row.rating">{{ row.rating }} ★</span>
            <span v-else class="muted">未评价</span>
          </template>
        </el-table-column>
        <el-table-column prop="messageCount" label="消息数" width="90" />
        <el-table-column label="开始时间" width="160">
          <template #default="{ row }">{{ row.startTime?.replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="query.size"
          v-model:current-page="query.page"
          @current-change="loadSessions"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  adminSessionsApi,
  chatHotQuestionsApi,
  chatResolutionApi,
  chatTrendApi
} from '@/api/chat'
import type { ChatAdminSession, HotQuestionVO, ResolutionStats, TrendPoint } from '@/types'
import { MEMBER_LEVEL_TEXT } from '@/types'

echarts.use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

type ChartInstance = ReturnType<typeof echarts.init>

const STATUS_MAP: Record<number, { text: string; type: 'primary' | 'success' | 'warning' | 'info' }> = {
  0: { text: '进行中', type: 'primary' },
  1: { text: '已结束', type: 'success' },
  2: { text: '已转人工', type: 'warning' },
  3: { text: '超时回收', type: 'info' }
}

// ---------- ECharts ----------
const trendRef = ref<HTMLElement | null>(null)
const pieRef = ref<HTMLElement | null>(null)
const barRef = ref<HTMLElement | null>(null)

let trendChart: ChartInstance | null = null
let pieChart: ChartInstance | null = null
let barChart: ChartInstance | null = null

const renderTrend = (points: TrendPoint[]) => {
  if (!trendRef.value) return
  trendChart = trendChart || echarts.init(trendRef.value)
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.date.slice(5))
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '对话量',
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.2 },
        data: points.map((p) => p.count)
      }
    ]
  })
}

const renderPie = (stats: ResolutionStats) => {
  if (!pieRef.value) return
  pieChart = pieChart || echarts.init(pieRef.value)
  pieChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        center: ['50%', '45%'],
        data: [
          { name: `AI 解决 (${stats.aiResolveRate}%)`, value: stats.aiResolved },
          { name: `转人工 (${stats.transferRate}%)`, value: stats.transferred },
          { name: '进行中', value: stats.active }
        ]
      }
    ]
  })
}

const renderBar = (questions: HotQuestionVO[]) => {
  if (!barRef.value) return
  barChart = barChart || echarts.init(barRef.value)
  barChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 60, right: 30, top: 20, bottom: 80 },
    xAxis: {
      type: 'category',
      data: questions.map((q) => q.question.slice(0, 12) + (q.question.length > 12 ? '…' : '')),
      axisLabel: { interval: 0, rotate: 30 }
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '提问次数',
        type: 'bar',
        barMaxWidth: 32,
        itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] },
        data: questions.map((q) => q.askCount)
      }
    ]
  })
}

/** 热问榜列表（表格展示提问次数与转人工次数） */
const hotQuestions = ref<HotQuestionVO[]>([])

const loadAnalytics = async () => {
  try {
    const [trend, resolution, hot] = await Promise.all([
      chatTrendApi(7),
      chatResolutionApi(),
      chatHotQuestionsApi(10)
    ])
    renderTrend(trend)
    renderPie(resolution)
    renderBar(hot)
    hotQuestions.value = hot
  } catch {
    // 图表加载失败不阻塞页面
  }
}

const onResize = () => {
  trendChart?.resize()
  pieChart?.resize()
  barChart?.resize()
}

// ---------- 历史会话表格 ----------
const tableLoading = ref(false)
const list = ref<ChatAdminSession[]>([])
const total = ref(0)
const query = reactive<{
  dateRange: [string, string] | null
  rating?: number
  transferFlag?: number
  page: number
  size: number
}>({
  dateRange: null,
  page: 1,
  size: 10
})

const loadSessions = async () => {
  tableLoading.value = true
  try {
    const res = await adminSessionsApi({
      startDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
      rating: query.rating,
      transferFlag: query.transferFlag,
      page: query.page,
      size: query.size
    })
    list.value = res.records
    total.value = res.total
  } finally {
    tableLoading.value = false
  }
}

const onFilter = () => {
  query.page = 1
  loadSessions()
}

// ---------- 生命周期 ----------
let resizeObs: ResizeObserver | null = null

onMounted(() => {
  loadAnalytics()
  loadSessions()
  resizeObs = new ResizeObserver(onResize)
  ;[trendRef.value, pieRef.value, barRef.value].forEach((el) => el && resizeObs?.observe(el))
})

onUnmounted(() => {
  resizeObs?.disconnect()
  trendChart?.dispose()
  pieChart?.dispose()
  barChart?.dispose()
  trendChart = pieChart = barChart = null
})
</script>

<style scoped>
.chat-analytics {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.chart-card:last-child {
  grid-column: 1 / -1;
}
.chart {
  height: 260px;
}
.trend-chart,
.pie-chart {
  height: 260px;
}
.bar-chart {
  height: 300px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.header-title {
  font-weight: 600;
}
.filters {
  display: flex;
  gap: 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.muted {
  color: #c0c4cc;
}
</style>
