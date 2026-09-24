import { ref, type Ref } from 'vue'

/**
 * 通用请求组合式函数：统一管理 loading / data / error，支持立即执行
 * @example
 * const { loading, data, run } = useRequest(() => adminSessionsApi(params))
 * onMounted(run)
 */
export function useRequest<T>(fetcher: () => Promise<T>, immediate = false) {
  const loading = ref(false)
  const data = ref<T | null>(null) as Ref<T | null>
  const error = ref<Error | null>(null)

  const run = async (): Promise<T | null> => {
    loading.value = true
    error.value = null
    try {
      data.value = await fetcher()
    } catch (e) {
      error.value = e as Error
    } finally {
      loading.value = false
    }
    return data.value
  }

  if (immediate) {
    run()
  }

  return { loading, data, error, run }
}
