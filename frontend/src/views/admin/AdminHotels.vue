<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">酒店管理</span>
        <div>
          <el-button v-if="userStore.isAdmin" type="primary" @click="openCreate">新增酒店</el-button>
          <el-button v-if="userStore.isSysAdmin" type="success" style="margin-left: 12px" @click="openOperator">
            新增经营者
          </el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="酒店名称" min-width="170" />
      <el-table-column prop="city" label="城市" width="90" />
      <el-table-column prop="address" label="地址" min-width="180" />
      <el-table-column label="星级" width="80">
        <template #default="{ row }">{{ row.starLevel }}星</template>
      </el-table-column>
      <el-table-column label="经营者ID" width="90">
        <template #default="{ row }">{{ row.ownerId }}</template>
      </el-table-column>
      <el-table-column label="营业状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '营业中' : '已下架' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="330">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openRoomTypes(row)">房型管理</el-button>
          <el-button size="small" type="warning" @click="openStaff(row)">前台账号</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.status === 1 ? 'danger' : 'success'"
            :loading="statusSavingId === row.id"
            @click="toggleHotelStatus(row)"
          >
            {{ row.status === 1 ? '下架' : '上架' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ==================== 酒店新增/编辑 ==================== -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑酒店' : '新增酒店'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="酒店名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入酒店名称" clearable />
        </el-form-item>
        <el-form-item label="城市" prop="city">
          <el-input v-model="form.city" placeholder="如：上海" clearable />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" placeholder="详细地址" clearable />
        </el-form-item>
        <el-form-item label="星级" prop="starLevel">
          <el-select v-model="form.starLevel" placeholder="选择星级" style="width: 100%">
            <el-option v-for="i in 5" :key="i" :label="`${i}星`" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!form.id && userStore.isSysAdmin" label="经营者" prop="ownerId">
          <el-select v-model="form.ownerId" placeholder="选择经营者" style="width: 100%">
            <el-option v-for="op in operators" :key="op.id" :label="`${op.nickname || op.username}（${op.username}）`" :value="op.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else-if="!form.id" label="经营者">
          <el-tag type="success">归自己名下（{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}）</el-tag>
        </el-form-item>
        <el-form-item v-if="form.id" label="营业状态">
          <el-radio-group v-model="form.status">
            <el-radio-button :value="1">营业中</el-radio-button>
            <el-radio-button :value="0">已下架</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" placeholder="酒店前台电话" clearable />
        </el-form-item>
        <el-form-item label="封面图URL">
          <el-input v-model="form.coverImg" placeholder="https://...（首页/搜索列表展示）" clearable />
        </el-form-item>
        <el-form-item label="图片列表">
          <el-input v-model="form.imagesText" type="textarea" :rows="3" placeholder="每行一个图片URL，保存时自动合并，供用户放大查看" />
        </el-form-item>
        <el-form-item label="纬度">
          <el-input-number v-model="form.latitude" :precision="6" :step="0.0001" :min="-90" :max="90" style="width: 100%" placeholder="如 31.2402" />
        </el-form-item>
        <el-form-item label="经度">
          <el-input-number v-model="form.longitude" :precision="6" :step="0.0001" :min="-180" :max="180" style="width: 100%" placeholder="如 121.4900" />
        </el-form-item>
        <el-form-item label="入住时间">
          <el-time-select v-model="form.checkinTime" start="00:00" step="00:30" end="23:30" placeholder="入住时间" />
        </el-form-item>
        <el-form-item label="退房时间">
          <el-time-select v-model="form.checkoutTime" start="00:00" step="00:30" end="23:30" placeholder="退房时间" />
        </el-form-item>
        <el-form-item label="酒店简介">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="酒店特色、设施等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增经营者 -->
    <el-dialog v-model="opDialogVisible" title="新增酒店经营者" width="440px">
      <el-form ref="opFormRef" :model="opForm" :rules="opRules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="opForm.username" placeholder="登录账号（3-20位）" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="opForm.password" type="password" placeholder="登录密码（6-20位）" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="opForm.nickname" placeholder="经营者昵称（选填）" clearable />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="opForm.phone" placeholder="联系电话（选填）" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="opDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="opSaving" @click="onSaveOperator">创建</el-button>
      </template>
    </el-dialog>

    <!-- 前台账号管理 -->
    <el-dialog v-model="staffDialogVisible" :title="`前台账号 - ${staffHotel?.name || ''}`" width="560px">
      <div class="drawer-toolbar">
        <span class="tip">前台账号仅可操作该酒店房间状态（入住/清理/空闲）</span>
        <el-button type="primary" size="small" @click="openStaffCreate">新增前台账号</el-button>
      </div>
      <el-table v-loading="staffLoading" :data="staffList" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="nickname" label="昵称" min-width="140" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="warning" @click="resetStaffPwd(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!staffLoading && !staffList.length" description="暂无前台账号" />
    </el-dialog>

    <!-- 新增前台账号 -->
    <el-dialog v-model="staffCreateVisible" title="新增前台账号" width="420px">
      <el-form ref="staffFormRef" :model="staffForm" :rules="staffRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="staffForm.username" placeholder="登录账号（3-20位）" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="staffForm.password" type="password" placeholder="登录密码（6-20位）" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="staffForm.nickname" placeholder="选填" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="staffCreateVisible = false">取消</el-button>
        <el-button type="primary" :loading="staffSaving" @click="onSaveStaff">创建</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 房型管理抽屉 ==================== -->
    <el-drawer v-model="drawerVisible" :title="`房型管理 - ${currentHotel?.name || ''}`" size="820px">
      <div class="drawer-toolbar">
        <el-button type="primary" @click="openRoomTypeCreate">新增房型</el-button>
      </div>
      <el-table v-loading="rtLoading" :data="roomTypes" size="small">
        <el-table-column prop="name" label="房型名称" min-width="110" />
        <el-table-column prop="bedType" label="床型" width="70" />
        <el-table-column prop="area" label="面积" width="70">
          <template #default="{ row }">{{ row.area ? `${row.area}m²` : '-' }}</template>
        </el-table-column>
        <el-table-column prop="maxGuests" label="人数" width="60" />
        <el-table-column label="价格" width="100">
          <template #default="{ row }">￥{{ row.price }}</template>
        </el-table-column>
        <el-table-column label="早餐" width="60">
          <template #default="{ row }">{{ row.breakfast === 1 ? '含早' : '无' }}</template>
        </el-table-column>
        <el-table-column prop="roomCount" label="房间数" width="70" />
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openRooms(row)">房间</el-button>
            <el-button size="small" @click="openRoomTypeEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="removeRoomType(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rtLoading && !roomTypes.length" description="暂无房型，点击右上角新增" />
    </el-drawer>

    <!-- 房型新增/编辑 -->
    <el-dialog v-model="rtDialogVisible" :title="rtForm.id ? '编辑房型' : '新增房型'" width="480px">
      <el-form ref="rtFormRef" :model="rtForm" :rules="rtRules" label-width="100px">
        <el-form-item label="房型名称" prop="name">
          <el-input v-model="rtForm.name" placeholder="如：大床房 / 双床房 / 套房" clearable />
        </el-form-item>
        <el-form-item label="床型">
          <el-select v-model="rtForm.bedType" placeholder="请选择" clearable style="width: 100%">
            <el-option label="大床" value="大床" />
            <el-option label="双床" value="双床" />
          </el-select>
        </el-form-item>
        <el-form-item label="面积(m²)">
          <el-input-number v-model="rtForm.area" :min="0" :max="500" style="width: 100%" />
        </el-form-item>
        <el-form-item label="最多入住" prop="maxGuests">
          <el-input-number v-model="rtForm.maxGuests" :min="1" :max="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="门市价(元)" prop="price">
          <el-input-number v-model="rtForm.price" :min="1" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="含早餐">
          <el-switch v-model="rtForm.breakfast" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="在售">
          <el-switch v-model="rtForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rtDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="rtSaving" @click="onSaveRoomType">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 房间管理弹窗 ==================== -->
    <el-dialog v-model="roomsDialogVisible" :title="`房间管理 - ${currentType?.name || ''}`" width="720px">
      <div class="rooms-toolbar">
        <el-button type="primary" size="small" @click="openBatch">批量生成房间</el-button>
        <span class="tip">房间号 = 楼层×100 + 编号（如 8 层 1-10 → 801~810）</span>
      </div>
      <el-table v-loading="roomsLoading" :data="rooms" size="small" max-height="420">
        <el-table-column prop="roomNo" label="房间号" width="90" />
        <el-table-column prop="floor" label="楼层" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="ROOM_STATUS[row.status]?.type">{{ ROOM_STATUS[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在住" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.occupied" type="danger" size="small">在住</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="250">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <el-button size="small" @click="setRoomStatus(row, 2)">标为打扫中</el-button>
              <el-button size="small" type="warning" @click="setRoomStatus(row, 1)">标为停用维修</el-button>
            </template>
            <template v-else-if="row.status === 2">
              <el-button size="small" type="success" @click="setRoomStatus(row, 0)">标为空闲</el-button>
              <el-button size="small" type="warning" @click="setRoomStatus(row, 1)">标为停用维修</el-button>
            </template>
            <template v-else>
              <el-button size="small" type="success" @click="setRoomStatus(row, 0)">恢复空闲</el-button>
            </template>
            <el-button size="small" type="danger" @click="removeRoom(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!roomsLoading && !rooms.length" description="暂无房间，点击「批量生成房间」" />
    </el-dialog>

    <!-- 批量生成房间 -->
    <el-dialog v-model="batchDialogVisible" title="批量生成房间" width="420px">
      <el-form :model="batchForm" label-width="90px">
        <el-form-item label="楼层">
          <el-input-number v-model="batchForm.floor" :min="1" :max="99" style="width: 100%" />
        </el-form-item>
        <el-form-item label="起始编号">
          <el-input-number v-model="batchForm.startNo" :min="1" :max="99" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束编号">
          <el-input-number v-model="batchForm.endNo" :min="1" :max="99" style="width: 100%" />
        </el-form-item>
        <el-alert type="info" :closable="false" title="将生成 楼层×100+编号 的房间号，已存在的自动跳过" />
      </el-form>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSaving" @click="onBatchCreate">生成</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  adminCreateHotelApi,
  adminCreateOperatorApi,
  adminHotelsApi,
  adminOperatorsApi,
  adminUpdateHotelApi,
  batchCreateRoomsApi,
  createHotelStaffApi,
  createRoomTypeApi,
  deleteRoomApi,
  deleteRoomTypeApi,
  hotelStaffApi,
  resetStaffPasswordApi,
  roomsApi,
  roomTypesApi,
  updateRoomStatusApi,
  updateRoomTypeApi
} from '@/api/admin'
import { useUserStore } from '@/stores/user'
import {
  ROOM_STATUS,
  type BatchRoomForm,
  type FrontDeskForm,
  type HotelSaveForm,
  type HotelVO,
  type OperatorCreateForm,
  type OperatorVO,
  type RoomTypeSaveForm,
  type RoomTypeVO,
  type RoomVO
} from '@/types'

const userStore = useUserStore()

// ---------- 酒店列表 ----------
const loading = ref(false)
const list = ref<HotelVO[]>([])
const operators = ref<OperatorVO[]>([])
/** 正在上下架的酒店ID：请求期间只让该行按钮转圈 */
const statusSavingId = ref<number | null>(null)

const load = async () => {
  loading.value = true
  try {
    list.value = await adminHotelsApi()
  } catch (e) {
    ElMessage.error((e as Error).message || '酒店列表加载失败')
  } finally {
    loading.value = false
  }
}

/** HotelVO → HotelSaveForm：只改部分字段时用于回传完整表单 */
const toSaveForm = (row: HotelVO, status?: number): HotelSaveForm => ({
  name: row.name,
  city: row.city,
  address: row.address,
  starLevel: row.starLevel,
  phone: row.phone,
  coverImg: row.coverImg,
  images: row.images,
  latitude: row.latitude,
  longitude: row.longitude,
  checkinTime: row.checkinTime || '14:00',
  checkoutTime: row.checkoutTime || '12:00',
  description: row.description,
  status
})

/** 上下架：复用酒店更新接口，只有 status 变化，其余字段原样回传 */
const toggleHotelStatus = async (row: HotelVO) => {
  const nextStatus = row.status === 1 ? 0 : 1
  const action = nextStatus === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(`确定${action}酒店「${row.name}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  statusSavingId.value = row.id
  try {
    const updated = await adminUpdateHotelApi(row.id, toSaveForm(row, nextStatus))
    const index = list.value.findIndex((h) => h.id === row.id)
    // 后端返回最新 HotelVO，存在则就地更新该行，避免整表重载
    if (index > -1 && updated) list.value[index] = updated
    else row.status = nextStatus
    ElMessage.success(`酒店已${action}`)
  } catch (e) {
    ElMessage.error((e as Error).message || `${action}失败`)
  } finally {
    statusSavingId.value = null
  }
}

// ---------- 酒店新增/编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<
  HotelSaveForm & { id?: number; imagesText?: string }
>({
  name: '',
  city: '',
  address: '',
  starLevel: 3,
  ownerId: undefined,
  phone: '',
  coverImg: '',
  imagesText: '',
  latitude: undefined,
  longitude: undefined,
  checkinTime: '14:00',
  checkoutTime: '12:00',
  description: '',
  status: undefined
})
const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入酒店名称', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  address: [{ required: true, message: '请输入地址', trigger: 'blur' }],
  starLevel: [{ required: true, message: '请选择星级', trigger: 'change' }],
  ...(userStore.isSysAdmin
    ? { ownerId: [{ required: true, message: '请选择经营者', trigger: 'change' }] }
    : {})
}))

const openCreate = async () => {
  try {
    if (userStore.isSysAdmin && !operators.value.length) {
      operators.value = await adminOperatorsApi()
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '经营者列表加载失败')
  }
  Object.assign(form, {
    id: undefined, name: '', city: '', address: '', starLevel: 3,
    ownerId: undefined, phone: '', coverImg: '', imagesText: '',
    latitude: undefined, longitude: undefined,
    checkinTime: '14:00', checkoutTime: '12:00', description: '', status: undefined
  })
  dialogVisible.value = true
}

const openEdit = (row: HotelVO) => {
  Object.assign(form, {
    id: row.id, name: row.name, city: row.city, address: row.address,
    starLevel: row.starLevel, ownerId: row.ownerId, phone: row.phone || '',
    coverImg: row.coverImg || '',
    imagesText: (row.images || '').split(',').filter(Boolean).join('\n'),
    latitude: row.latitude, longitude: row.longitude,
    checkinTime: row.checkinTime || '14:00', checkoutTime: row.checkoutTime || '12:00',
    description: row.description || '', status: row.status
  })
  dialogVisible.value = true
}

const onSave = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const payload: HotelSaveForm = {
      name: form.name, city: form.city, address: form.address, starLevel: form.starLevel,
      phone: form.phone, coverImg: form.coverImg,
      images: (form.imagesText || '').split('\n').map((s) => s.trim()).filter(Boolean).join(','),
      latitude: form.latitude ?? undefined,
      longitude: form.longitude ?? undefined,
      checkinTime: form.checkinTime, checkoutTime: form.checkoutTime,
      description: form.description,
      // 编辑时一并提交营业状态；新增不传，由后端使用默认值
      status: form.id ? form.status : undefined
    }
    if (form.id) {
      await adminUpdateHotelApi(form.id, payload)
      ElMessage.success('酒店信息已更新')
    } else {
      if (userStore.isSysAdmin) payload.ownerId = form.ownerId
      await adminCreateHotelApi(payload)
      ElMessage.success('酒店创建成功')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '酒店信息保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 新增经营者 ----------
const opDialogVisible = ref(false)
const opSaving = ref(false)
const opFormRef = ref<FormInstance>()
const opForm = reactive<OperatorCreateForm>({ username: '', password: '', nickname: '', phone: '' })
const opRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为3-20位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20位', trigger: 'blur' }
  ]
}

const openOperator = () => {
  Object.assign(opForm, { username: '', password: '', nickname: '', phone: '' })
  opDialogVisible.value = true
}

const onSaveOperator = async () => {
  if (!opFormRef.value) return
  try {
    await opFormRef.value.validate()
  } catch {
    return
  }
  opSaving.value = true
  try {
    await adminCreateOperatorApi({
      username: opForm.username,
      password: opForm.password,
      nickname: opForm.nickname || undefined,
      phone: opForm.phone || undefined
    })
    ElMessage.success('经营者账号已创建，可登录后自助注册酒店')
    opDialogVisible.value = false
    if (userStore.isSysAdmin) {
      operators.value = await adminOperatorsApi() // 刷新经营者下拉
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '经营者账号创建失败')
  } finally {
    opSaving.value = false
  }
}

// ---------- 前台账号管理 ----------
const staffDialogVisible = ref(false)
const staffLoading = ref(false)
const staffHotel = ref<HotelVO | null>(null)
const staffList = ref<OperatorVO[]>([])

const openStaff = async (hotel: HotelVO) => {
  staffHotel.value = hotel
  staffDialogVisible.value = true
  await loadStaff()
}

const loadStaff = async () => {
  if (!staffHotel.value) return
  staffLoading.value = true
  try {
    staffList.value = await hotelStaffApi(staffHotel.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message || '前台账号加载失败')
  } finally {
    staffLoading.value = false
  }
}

const staffCreateVisible = ref(false)
const staffSaving = ref(false)
const staffFormRef = ref<FormInstance>()
const staffForm = reactive<FrontDeskForm>({ username: '', password: '', nickname: '' })
const staffRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为3-20位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20位', trigger: 'blur' }
  ]
}

const openStaffCreate = () => {
  Object.assign(staffForm, { username: '', password: '', nickname: '' })
  staffCreateVisible.value = true
}

const onSaveStaff = async () => {
  if (!staffFormRef.value) return
  try {
    await staffFormRef.value.validate()
  } catch {
    return
  }
  staffSaving.value = true
  try {
    await createHotelStaffApi(staffHotel.value!.id, {
      username: staffForm.username,
      password: staffForm.password,
      nickname: staffForm.nickname || undefined
    })
    ElMessage.success('前台账号已创建，可用该账号登录操作房间状态')
    staffCreateVisible.value = false
    loadStaff()
  } catch (e) {
    ElMessage.error((e as Error).message || '前台账号创建失败')
  } finally {
    staffSaving.value = false
  }
}

const resetStaffPwd = async (row: OperatorVO) => {
  const { value } = await ElMessageBox.prompt(`为前台账号「${row.username}」设置新密码`, '重置密码', {
    inputType: 'password',
    inputPattern: /^.{6,20}$/,
    inputErrorMessage: '密码长度为6-20位'
  }).catch(() => ({ value: '' }))
  if (!value) return
  try {
    await resetStaffPasswordApi(staffHotel.value!.id, row.id, value)
    ElMessage.success('密码已重置')
    loadStaff()
  } catch (e) {
    ElMessage.error((e as Error).message || '密码重置失败')
  }
}

// ---------- 房型管理 ----------
const drawerVisible = ref(false)
const currentHotel = ref<HotelVO | null>(null)
const roomTypes = ref<RoomTypeVO[]>([])
const rtLoading = ref(false)

const openRoomTypes = async (hotel: HotelVO) => {
  currentHotel.value = hotel
  drawerVisible.value = true
  await loadRoomTypes()
}

const loadRoomTypes = async () => {
  if (!currentHotel.value) return
  rtLoading.value = true
  try {
    roomTypes.value = await roomTypesApi(currentHotel.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message || '房型列表加载失败')
  } finally {
    rtLoading.value = false
  }
}

const rtDialogVisible = ref(false)
const rtSaving = ref(false)
const rtFormRef = ref<FormInstance>()
const rtForm = reactive<RoomTypeSaveForm & { id?: number }>({
  hotelId: 0,
  name: '',
  bedType: '大床',
  area: 30,
  maxGuests: 2,
  price: 100,
  breakfast: 0,
  status: 1
})
const rtRules: FormRules = {
  name: [{ required: true, message: '请输入房型名称', trigger: 'blur' }],
  maxGuests: [{ required: true, message: '请填写最多入住人数', trigger: 'blur' }],
  price: [{ required: true, message: '请填写价格', trigger: 'blur' }]
}

const openRoomTypeCreate = () => {
  Object.assign(rtForm, {
    id: undefined, hotelId: currentHotel.value?.id, name: '', bedType: '大床',
    area: 30, maxGuests: 2, price: 100, breakfast: 0, status: 1
  })
  rtDialogVisible.value = true
}

const openRoomTypeEdit = (row: RoomTypeVO) => {
  Object.assign(rtForm, {
    id: row.id, hotelId: row.hotelId, name: row.name, bedType: row.bedType || '大床',
    area: row.area ?? 30, maxGuests: row.maxGuests, price: row.price,
    breakfast: row.breakfast, status: row.status
  })
  rtDialogVisible.value = true
}

const onSaveRoomType = async () => {
  if (!rtFormRef.value) return
  try {
    await rtFormRef.value.validate()
  } catch {
    return
  }
  rtSaving.value = true
  try {
    const payload: RoomTypeSaveForm = {
      hotelId: currentHotel.value!.id,
      name: rtForm.name,
      bedType: rtForm.bedType,
      area: rtForm.area,
      maxGuests: rtForm.maxGuests,
      price: rtForm.price,
      breakfast: rtForm.breakfast,
      status: rtForm.status
    }
    if (rtForm.id) {
      await updateRoomTypeApi(rtForm.id, payload)
      ElMessage.success('房型已更新')
    } else {
      await createRoomTypeApi(payload)
      ElMessage.success('房型创建成功')
    }
    rtDialogVisible.value = false
    loadRoomTypes()
  } catch (e) {
    ElMessage.error((e as Error).message || '房型保存失败')
  } finally {
    rtSaving.value = false
  }
}

const removeRoomType = async (row: RoomTypeVO) => {
  try {
    await ElMessageBox.confirm(`确定删除房型「${row.name}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteRoomTypeApi(row.id)
    ElMessage.success('房型已删除')
    loadRoomTypes()
  } catch (e) {
    ElMessage.error((e as Error).message || '房型删除失败')
  }
}

// ---------- 房间管理 ----------
const roomsDialogVisible = ref(false)
const currentType = ref<RoomTypeVO | null>(null)
const rooms = ref<RoomVO[]>([])
const roomsLoading = ref(false)

const openRooms = async (row: RoomTypeVO) => {
  currentType.value = row
  roomsDialogVisible.value = true
  await loadRooms()
}

const loadRooms = async () => {
  if (!currentType.value) return
  roomsLoading.value = true
  try {
    rooms.value = await roomsApi(currentType.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message || '房间列表加载失败')
  } finally {
    roomsLoading.value = false
  }
}

const batchDialogVisible = ref(false)
const batchSaving = ref(false)
const batchForm = reactive<BatchRoomForm>({ floor: 8, startNo: 1, endNo: 10 })

const openBatch = () => {
  Object.assign(batchForm, { floor: 8, startNo: 1, endNo: 10 })
  batchDialogVisible.value = true
}

const onBatchCreate = async () => {
  if (batchForm.startNo > batchForm.endNo) {
    ElMessage.warning('起始编号不能大于结束编号')
    return
  }
  if (batchForm.endNo - batchForm.startNo + 1 > 100) {
    ElMessage.warning('单次最多生成 100 个房间')
    return
  }
  batchSaving.value = true
  try {
    const created = await batchCreateRoomsApi(currentType.value!.id, batchForm)
    ElMessage.success(`已生成 ${created.length} 个房间`)
    batchDialogVisible.value = false
    loadRooms()
    loadRoomTypes() // 刷新房间数
  } catch (e) {
    ElMessage.error((e as Error).message || '房间生成失败')
  } finally {
    batchSaving.value = false
  }
}

const setRoomStatus = async (row: RoomVO, status: number) => {
  try {
    const updated = await updateRoomStatusApi(row.id, status)
    const index = rooms.value.findIndex((r) => r.id === row.id)
    // 用后端返回的最新 RoomVO 就地更新该行（含 occupied），避免整表重载
    if (index > -1 && updated) rooms.value[index] = updated
    else row.status = status
    ElMessage.success('状态已更新')
  } catch (e) {
    ElMessage.error((e as Error).message || '房间状态更新失败')
  }
}

const removeRoom = async (row: RoomVO) => {
  try {
    await ElMessageBox.confirm(`确定删除房间「${row.roomNo}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteRoomApi(row.id)
    ElMessage.success('房间已删除')
    loadRooms()
    loadRoomTypes()
  } catch (e) {
    ElMessage.error((e as Error).message || '房间删除失败')
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
.drawer-toolbar,
.rooms-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
