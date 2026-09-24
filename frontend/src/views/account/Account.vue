<template>
  <div class="account-page">
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span class="card-title">我的账号</span>
          </template>
          <el-descriptions v-loading="loading" :column="1" border>
            <el-descriptions-item label="用户名">{{ me?.username }}</el-descriptions-item>
            <el-descriptions-item label="昵称">{{ me?.nickname || '-' }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ ROLE_MAP[me?.role ?? -1] || '-' }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ me?.phone || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card>
          <template #header>
            <span class="card-title">修改密码</span>
          </template>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入原密码" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="form.newPassword" type="password" show-password placeholder="6-20位" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="onSubmit">保存新密码</el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePasswordApi, meApi } from '@/api/user'
import { useRequest } from '@/composables/useRequest'
import { useUserStore } from '@/stores/user'
import { ROLE_MAP } from '@/types'

const userStore = useUserStore()
/** 账号信息：loading / data / error 统一交给 useRequest 管理 */
const { loading, data: me, run } = useRequest(() => meApi())
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const onSubmit = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    await changePasswordApi({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码已修改，请使用新密码登录')
    userStore.logout()
    location.href = '/login'
  } catch (e) {
    ElMessage.error((e as Error).message || '密码修改失败')
  } finally {
    saving.value = false
  }
}

onMounted(run)
</script>

<style scoped>
.account-page {
  max-width: 960px;
  margin: 0 auto;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
}
.password {
  font-weight: 600;
  color: #f56c6c;
  letter-spacing: 1px;
}
</style>
