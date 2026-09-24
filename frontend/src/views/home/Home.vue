<template>
  <div class="home">
    <el-card class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="城市">
          <el-input v-model="query.city" placeholder="如：上海" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="星级">
          <el-select v-model="query.starLevel" placeholder="不限" clearable style="width: 120px">
            <el-option v-for="i in 5" :key="i" :label="`${i}星`" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="入住日期">
          <el-date-picker
            v-model="dates"
            type="daterange"
            range-separator="至"
            start-placeholder="入住"
            end-placeholder="离店"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">搜索房源</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div v-loading="loading" class="result-wrap">
      <el-empty
        v-if="loaded && !hotels.length"
        :description="error ? error.message : '暂无符合条件的酒店，换个条件试试'"
      />
      <el-row v-else :gutter="16" class="hotel-list">
        <el-col v-for="hotel in hotels" :key="hotel.hotelId" :span="8">
          <el-card shadow="hover" class="hotel-card" @click="goDetail(hotel)">
            <el-image
              v-if="hotel.coverImg"
              :src="hotel.coverImg"
              class="cover"
              fit="cover"
              :preview-src-list="hotelImages(hotel)"
              preview-teleported
              :hide-on-click-modal="true"
              @click.stop
            />
            <div class="hotel-name">{{ hotel.hotelName }}</div>
            <div class="hotel-info">{{ hotel.city }} · {{ hotel.address }}</div>
            <div class="hotel-info">
              <el-tag size="small" type="warning">{{ hotel.starLevel }}星</el-tag>
              <span class="price">￥{{ hotel.lowestPrice }}起</span>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { searchHotelsApi } from '@/api/search'
import { useRequest } from '@/composables/useRequest'
import type { HotelSearchVO } from '@/types'

const router = useRouter()
const dates = ref<[string, string] | null>(null)
const query = reactive<{ city?: string; starLevel?: number }>({})

/** 首页酒店推荐：loading / data / error 统一交给 useRequest 管理 */
const { loading, data: pageData, error, run } = useRequest(() => searchHotelsApi({ page: 1, size: 9 }))
const hotels = computed<HotelSearchVO[]>(() => pageData.value?.records ?? [])
/** 请求结束（成功或失败）后才展示空态，避免加载中闪出「暂无酒店」 */
const loaded = computed(() => !loading.value && (pageData.value !== null || error.value !== null))

const onSearch = async () => {
  const params: Record<string, string> = {}
  if (query.city) params.city = query.city
  if (query.starLevel) params.starLevel = String(query.starLevel)
  if (dates.value) {
    params.checkin = dates.value[0]
    params.checkout = dates.value[1]
  }
  router.push({ path: '/search', query: params })
}

const goDetail = (hotel: HotelSearchVO) => {
  router.push({
    path: '/search',
    query: {
      city: query.city || hotel.city,
      checkin: dates.value?.[0] || undefined,
      checkout: dates.value?.[1] || undefined
    }
  })
}

/** 酒店图片集合（封面 + 相册），点击可放大查看 */
const hotelImages = (hotel: HotelSearchVO): string[] =>
  [hotel.coverImg, ...(hotel.images || '').split(',').map((s) => s.trim())].filter((s) => !!s) as string[]

onMounted(run)
</script>

<style scoped>
.home {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px;
}
.result-wrap {
  min-height: 180px;
}
.search-card {
  margin-bottom: 20px;
}
.hotel-list {
  row-gap: 16px;
}
.hotel-card {
  cursor: pointer;
}
.cover {
  width: 100%;
  height: 140px;
  border-radius: 4px;
  margin-bottom: 10px;
}
.hotel-name {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 4px;
}
.hotel-info {
  color: #909399;
  font-size: 13px;
  margin-bottom: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.price {
  color: #f56c6c;
  font-weight: 600;
}
</style>
