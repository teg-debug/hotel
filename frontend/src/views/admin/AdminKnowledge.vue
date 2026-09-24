<template>
  <el-card>
    <template #header>
      <div class="header">
        <span class="header-title">知识库管理</span>
        <div>
          <el-select v-model="query.hotelId" placeholder="全部酒店" clearable style="width: 200px" @change="load">
            <el-option v-if="userStore.isSysAdmin" label="平台级" :value="PLATFORM_LEVEL" />
            <el-option v-for="h in hotels" :key="h.id" :label="h.name" :value="h.id" />
          </el-select>
          <el-button type="primary" style="margin-left: 12px" @click="openCreate">
            <el-icon><Plus /></el-icon>
            新增条目
          </el-button>
          <el-button type="success" @click="openImport">批量导入</el-button>
          <el-button @click="openUpload">
            <el-icon><Upload /></el-icon>
            文档上传
          </el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="所属酒店" width="150">
        <template #default="{ row }">
          <el-tag v-if="!row.hotelId" type="warning">平台级</el-tag>
          <span v-else>{{ hotelName(row.hotelId) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="answer" label="答案" min-width="240" show-overflow-tooltip />
      <el-table-column label="分类" width="110">
        <template #default="{ row }">{{ row.category || '-' }}</template>
      </el-table-column>
      <el-table-column label="命中阈值" width="100">
        <template #default="{ row }">{{ row.similarityThreshold }}</template>
      </el-table-column>
      <el-table-column prop="hitCount" label="命中次数" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status"
            :active-value="1"
            :inactive-value="0"
            :loading="statusSavingId === row.id"
            @change="toggleStatus(row)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !list.length" description="暂无知识条目" />

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

    <!-- ==================== 新增/编辑条目 ==================== -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑知识条目' : '新增知识条目'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="问题" prop="question">
          <el-input v-model="form.question" type="textarea" :rows="2" placeholder="用户可能提出的问题" />
        </el-form-item>
        <el-form-item label="答案" prop="answer">
          <el-input v-model="form.answer" type="textarea" :rows="4" placeholder="标准回复内容" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如：设施 / 政策（选填）" clearable />
        </el-form-item>
        <el-form-item label="命中阈值">
          <el-input-number
            v-model="form.similarityThreshold"
            :min="0.1"
            :max="1"
            :step="0.05"
            :precision="2"
          />
          <span class="tip">相似度达到该阈值才命中，默认 0.7</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 批量导入 ==================== -->
    <el-dialog v-model="importVisible" title="批量导入" width="600px">
      <el-alert type="info" :closable="false" class="import-tip">
        <template #title>格式要求</template>
        每行一条，用竖线分隔问题与答案，例如：<br />
        早餐时间|早餐供应时间为 06:30-10:00<br />
        空行会自动跳过，问题与答案都不能为空。
      </el-alert>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="8"
        class="import-input"
        placeholder="问题|答案（每行一条）"
      />
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="onImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 文档上传 ==================== -->
    <el-dialog v-model="uploadVisible" title="文档上传" width="560px">
      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :limit="1"
        :show-file-list="true"
        accept=".pdf,.txt,.md"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-button type="primary">选择文件</el-button>
      </el-upload>
      <div class="tip">
        支持 PDF / TXT / Markdown 文档，解析后自动写入知识库；所属酒店取当前筛选的酒店。
      </div>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="onUpload">开始上传</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type UploadFile,
  type UploadInstance
} from 'element-plus'
import { Plus, Upload } from '@element-plus/icons-vue'
import { adminHotelsApi } from '@/api/admin'
import {
  knowledgeDeleteApi,
  knowledgeImportApi,
  knowledgePageApi,
  knowledgeUpdateApi,
  knowledgeUploadApi
} from '@/api/chat'
import { useUserStore } from '@/stores/user'
import type { HotelVO, KnowledgeEntry } from '@/types'

const userStore = useUserStore()

/** 平台级知识的筛选值：传接口时统一转为 undefined（即不传 hotelId） */
const PLATFORM_LEVEL = 0

const loading = ref(false)
const list = ref<KnowledgeEntry[]>([])
const total = ref(0)
const hotels = ref<HotelVO[]>([])
const query = reactive<{ hotelId?: number; page: number; size: number }>({ page: 1, size: 10 })
/** 正在切换启用状态的条目ID：请求期间只让该行开关转圈 */
const statusSavingId = ref<number | null>(null)

/** 筛选值 → 接口参数：清空与「平台级」等价，都不传 hotelId */
const hotelIdParam = () =>
  query.hotelId && query.hotelId > 0 ? query.hotelId : undefined

const hotelName = (hotelId?: number) =>
  hotels.value.find((h) => h.id === hotelId)?.name || `酒店 #${hotelId}`

const load = async () => {
  loading.value = true
  try {
    const data = await knowledgePageApi({
      hotelId: hotelIdParam(),
      page: query.page,
      size: query.size
    })
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error((e as Error).message || '知识库加载失败')
  } finally {
    loading.value = false
  }
}

/** 启用/停用：复用更新接口，只改 status，其余字段原样回传 */
const toggleStatus = async (row: KnowledgeEntry) => {
  const nextStatus = row.status === 1 ? 0 : 1
  statusSavingId.value = row.id
  try {
    const updated = await knowledgeUpdateApi(row.id, {
      question: row.question,
      answer: row.answer,
      category: row.category || undefined,
      similarityThreshold: row.similarityThreshold,
      status: nextStatus
    })
    row.status = updated?.status ?? nextStatus
    ElMessage.success(nextStatus === 1 ? '条目已启用' : '条目已停用')
  } catch (e) {
    ElMessage.error((e as Error).message || '状态更新失败')
  } finally {
    statusSavingId.value = null
  }
}

// ---------- 新增/编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<{
  id?: number
  question: string
  answer: string
  category?: string
  similarityThreshold: number
  status: number
}>({
  id: undefined,
  question: '',
  answer: '',
  category: '',
  similarityThreshold: 0.7,
  status: 1
})

const rules: FormRules = {
  question: [{ required: true, message: '请输入问题', trigger: 'blur' }],
  answer: [{ required: true, message: '请输入答案', trigger: 'blur' }]
}

const openCreate = () => {
  Object.assign(form, {
    id: undefined,
    question: '',
    answer: '',
    category: '',
    similarityThreshold: 0.7,
    status: 1
  })
  dialogVisible.value = true
}

const openEdit = (row: KnowledgeEntry) => {
  Object.assign(form, {
    id: row.id,
    question: row.question,
    answer: row.answer,
    category: row.category || '',
    similarityThreshold: row.similarityThreshold ?? 0.7,
    status: row.status
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
  const hotelId = hotelIdParam()
  if (!form.id && !hotelId && !userStore.isSysAdmin) {
    ElMessage.warning('平台级知识仅系统管理员可维护，请先在顶部选择具体酒店')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await knowledgeUpdateApi(form.id, {
        question: form.question,
        answer: form.answer,
        category: form.category || undefined,
        similarityThreshold: form.similarityThreshold,
        status: form.status
      })
      ElMessage.success('知识条目已更新')
    } else {
      await knowledgeImportApi({
        hotelId,
        category: form.category || undefined,
        items: [{ question: form.question, answer: form.answer }]
      })
      ElMessage.success('知识条目已新增')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '知识条目保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 批量导入 ----------
const importVisible = ref(false)
const importing = ref(false)
const importText = ref('')

const openImport = () => {
  importText.value = ''
  importVisible.value = true
}

const onImport = async () => {
  const lines = importText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  if (!lines.length) {
    ElMessage.warning('请粘贴要导入的内容，每行一条，格式为「问题|答案」')
    return
  }
  const items: { question: string; answer: string }[] = []
  for (const line of lines) {
    const index = line.indexOf('|')
    const question = index > -1 ? line.slice(0, index).trim() : ''
    const answer = index > -1 ? line.slice(index + 1).trim() : ''
    if (!question || !answer) {
      ElMessage.warning('存在格式不正确的行，请按「问题|答案」每行一条填写（问题与答案都不能为空）')
      return
    }
    items.push({ question, answer })
  }
  const hotelId = hotelIdParam()
  if (!hotelId && !userStore.isSysAdmin) {
    ElMessage.warning('平台级知识仅系统管理员可维护，请先在顶部选择具体酒店')
    return
  }
  importing.value = true
  try {
    const count = await knowledgeImportApi({ hotelId, items })
    ElMessage.success(`已导入 ${count} 条知识`)
    importVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '批量导入失败')
  } finally {
    importing.value = false
  }
}

// ---------- 文档上传 ----------
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref<UploadInstance>()
const selectedFile = ref<File | null>(null)

const openUpload = () => {
  selectedFile.value = null
  uploadVisible.value = true
}

const onFileChange = (file: UploadFile) => {
  selectedFile.value = file.raw ?? null
}

const onFileRemove = () => {
  selectedFile.value = null
}

const onUpload = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要上传的文档')
    return
  }
  const hotelId = hotelIdParam()
  if (!hotelId && !userStore.isSysAdmin) {
    ElMessage.warning('平台级知识仅系统管理员可维护，请先在顶部选择具体酒店')
    return
  }
  uploading.value = true
  try {
    const count = await knowledgeUploadApi(selectedFile.value, hotelId)
    ElMessage.success(`文档解析完成，新增 ${count} 条知识`)
    uploadVisible.value = false
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '文档上传失败')
  } finally {
    uploading.value = false
  }
}

// ---------- 删除 ----------
const remove = async (row: KnowledgeEntry) => {
  try {
    await ElMessageBox.confirm(`确定删除知识条目「${row.question}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await knowledgeDeleteApi(row.id)
    ElMessage.success('知识条目已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || '知识条目删除失败')
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
.tip {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
.import-tip {
  margin-bottom: 12px;
}
.import-input {
  width: 100%;
}
</style>
