<template>
  <div v-loading="loading" class="result-wrap">
    <el-card v-for="hotel in list" :key="hotel.hotelId" class="hotel-card" shadow="hover">
      <div class="hotel-row">
        <el-image
          v-if="hotel.coverImg"
          :src="hotel.coverImg"
          class="cover"
          fit="cover"
          :preview-src-list="hotelImages(hotel)"
          preview-teleported
          :initial-index="0"
          :hide-on-click-modal="true"
        />
        <div class="info">
          <div class="title">
            {{ hotel.hotelName }}
            <el-tag size="small" type="warning">{{ hotel.starLevel }}星</el-tag>
          </div>
          <div class="addr">
            {{ hotel.city }} · {{ hotel.address }}
            <el-link
              v-if="hasLocation(hotel)"
              type="primary"
              :underline="false"
              class="map-link"
              @click="openMap(hotel)"
            >
              查看地图
            </el-link>
          </div>
          <div v-if="hotel.description" class="desc" :class="{ expanded: descExpanded[hotel.hotelId] }">
            {{ hotel.description }}
          </div>
          <el-button
            v-if="hotel.description && hotel.description.length > 45"
            link
            type="primary"
            size="small"
            class="desc-toggle"
            @click="toggleDesc(hotel.hotelId)"
          >
            {{ descExpanded[hotel.hotelId] ? '收起' : '展开' }}
          </el-button>
          <el-table :data="hotel.availableRoomTypes" size="small">
            <el-table-column label="房型" min-width="110">
              <template #default="{ row }">
                <div class="rt-cell">
                  <el-image
                    v-if="row.imgUrl"
                    :src="row.imgUrl"
                    class="rt-img"
                    fit="cover"
                    :preview-src-list="[row.imgUrl]"
                    preview-teleported
                    :hide-on-click-modal="true"
                  />
                  <span>{{ row.name }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="maxGuests" label="可住人数" width="90" />
            <el-table-column label="早餐" width="80">
              <template #default="{ row }">{{ row.breakfast === 1 ? '含早' : '不含早' }}</template>
            </el-table-column>
            <el-table-column label="可用" width="80">
              <template #default="{ row }">{{ row.availableCount }}间</template>
            </el-table-column>
            <el-table-column label="价格" width="120">
              <template #default="{ row }">
                <span class="price">￥{{ row.price }}</span><span class="unit">/晚</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="goBook(hotel, row)">预订</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-card>

    <el-empty
      v-if="loaded && !list.length"
      :description="error ? error.message : '未找到符合条件的酒店'"
    />

    <div class="pager-wrap">
      <el-pagination
        v-if="total > 0"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size ?? 5"
        v-model:current-page="query.page"
        @current-change="load"
      />
    </div>

    <!-- 地理位置地图 -->
    <el-dialog v-model="mapVisible" :title="mapHotel?.hotelName || '酒店位置'" width="640px">
      <iframe v-if="mapSrc" :src="mapSrc" class="map-frame" />
      <div class="map-foot">
        <el-link v-if="mapHotel" type="primary" :href="amapUrl(mapHotel)" target="_blank">
          在高德地图中打开（可导航）
        </el-link>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchHotelsApi, type SearchParams } from '@/api/search'
import { useRequest } from '@/composables/useRequest'
import type { HotelSearchVO, RoomTypeSearchVO } from '@/types'

const route = useRoute()
const router = useRouter()

const query = reactive<SearchParams>({
  city: (route.query.city as string) || undefined,
  starLevel: route.query.starLevel ? Number(route.query.starLevel) : undefined,
  checkin: (route.query.checkin as string) || undefined,
  checkout: (route.query.checkout as string) || undefined,
  page: 1,
  size: 5
})

/** 搜索结果：loading / data / error 统一交给 useRequest 管理 */
const { loading, data: pageData, error, run } = useRequest(() => searchHotelsApi(query))
const list = computed<HotelSearchVO[]>(() => pageData.value?.records ?? [])
const total = computed(() => pageData.value?.total ?? 0)
/** 请求结束（成功或失败）后才展示空态，避免加载中闪出「未找到」 */
const loaded = computed(() => !loading.value && (pageData.value !== null || error.value !== null))

const load = () => run()

const goBook = (hotel: HotelSearchVO, roomType: RoomTypeSearchVO) => {
  router.push({
    path: '/booking',
    query: {
      hotelId: String(hotel.hotelId),
      roomTypeId: String(roomType.id),
      checkin: query.checkin || '',
      checkout: query.checkout || ''
    }
  })
}

/** 酒店图片集合（封面 + 相册），用于点击放大预览 */
const hotelImages = (hotel: HotelSearchVO): string[] =>
  [hotel.coverImg, ...(hotel.images || '').split(',').map((s) => s.trim())].filter((s) => !!s) as string[]

const hasLocation = (hotel: HotelSearchVO) =>
  hotel.latitude != null && hotel.longitude != null

// ---------- 酒店介绍（展开/收起） ----------
const descExpanded = reactive<Record<number, boolean>>({})

const toggleDesc = (hotelId: number) => {
  descExpanded[hotelId] = !descExpanded[hotelId]
}

// ---------- 地图 ----------
const mapVisible = ref(false)
const mapHotel = ref<HotelSearchVO | null>(null)
const mapSrc = computed(() => {
  const h = mapHotel.value
  if (!h || !hasLocation(h)) return ''
  const lat = h.latitude!
  const lng = h.longitude!
  // OpenStreetMap 内嵌地图（无需 key）
  return `https://www.openstreetmap.org/export/embed.html?bbox=${lng - 0.01},${lat - 0.01},${lng + 0.01},${lat + 0.01}&layer=mapnik&marker=${lat},${lng}`
})

const openMap = (hotel: HotelSearchVO) => {
  mapHotel.value = hotel
  mapVisible.value = true
}

const amapUrl = (hotel: HotelSearchVO) =>
  `https://uri.amap.com/marker?position=${hotel.longitude},${hotel.latitude}&name=${encodeURIComponent(hotel.hotelName)}`

onMounted(load)
</script>

<style scoped>
.result-wrap {
  min-height: 200px;
}
.hotel-card {
  margin-bottom: 16px;
}
.hotel-row {
  display: flex;
  gap: 16px;
}
.cover {
  width: 240px;
  height: 160px;
  border-radius: 4px;
  flex-shrink: 0;
  cursor: zoom-in;
}
.info {
  flex: 1;
}
.title {
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.addr {
  color: #909399;
  font-size: 13px;
  margin-bottom: 10px;
}
.map-link {
  margin-left: 12px;
  font-size: 13px;
}
.desc {
  position: relative;
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.desc.expanded {
  display: block;
  -webkit-line-clamp: unset;
}
.desc-toggle {
  margin: -4px 0 8px;
  padding: 0;
  height: 20px;
}
.rt-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rt-img {
  width: 40px;
  height: 30px;
  border-radius: 3px;
  cursor: zoom-in;
  flex-shrink: 0;
}
.price {
  color: #f56c6c;
  font-weight: 700;
}
.unit {
  color: #909399;
  font-size: 12px;
}
.pager-wrap {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
.map-frame {
  width: 100%;
  height: 380px;
  border: 0;
}
.map-foot {
  margin-top: 12px;
  text-align: center;
}
</style>
