<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Home, RefreshCw } from 'lucide-vue-next'
import { getBrands, getStores, type BrandInfo, type StoreInfo } from '../api/operations'
import BrandBadge from '../components/common/BrandBadge.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { normalizeBrandName } from '../utils/brand'

const stores = ref<StoreInfo[]>([])
const brands = ref<BrandInfo[]>([])
const loading = ref(false)
const error = ref('')

const activeStoreCount = computed(() => stores.value.filter((store) => !store.status || store.status === '营业中').length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [storeRows, brandRows] = await Promise.all([getStores(), getBrands()])
    stores.value = storeRows
    brands.value = brandRows
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '门店管理加载失败'
  } finally {
    loading.value = false
  }
}

function brandName(id: number) {
  return normalizeBrandName(brands.value.find((brand) => brand.id === id)?.name || '-')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page-panel stores-page">
    <PageHeader>
      <template #actions>
        <div class="store-toolbar">
          <button class="ghost-button" type="button" :disabled="loading" @click="load">
            <RefreshCw :size="16" />刷新
          </button>
          <button class="ghost-button danger" type="button" disabled>清空全部数据</button>
        </div>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div class="metric-grid">
      <article class="metric-card">
        <span>门店总数</span>
        <b>{{ stores.length }}</b>
      </article>
      <article class="metric-card">
        <span>营业中</span>
        <b>{{ activeStoreCount }}</b>
        <small>停业或停用 {{ stores.length - activeStoreCount }} 家</small>
      </article>
      <article class="metric-card">
        <span>品牌数量</span>
        <b>{{ brands.length }}</b>
      </article>
    </div>

    <section class="content-card">
      <div class="table-heading">
        <div class="stores-title">
            <Home :size="20" />
            <div>
              <h3>门店档案</h3>
            </div>
          </div>
      </div>
      <div v-if="loading && !stores.length" class="empty-state compact">正在读取门店档案...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>门店</th>
              <th>编号</th>
              <th>品牌</th>
              <th>区域</th>
              <th>负责人</th>
              <th>开业日期</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="store in stores" :key="store.id">
              <td><b>{{ store.name }}</b><small>{{ store.id }}</small></td>
              <td>{{ store.code || '-' }}</td>
              <td><BrandBadge :brand-name="store.brandName || brandName(store.brandId)" /></td>
              <td>{{ store.area || '-' }}</td>
              <td>{{ store.manager || '-' }}</td>
              <td>{{ store.openDate || '-' }}</td>
              <td><span class="status-badge" :class="store.status === '营业中' ? 'ok' : 'warn'">{{ store.status || '未设置' }}</span></td>
              <td>
                <div class="row-actions">
                  <button class="mini-button" type="button" disabled>编辑</button>
                  <button class="mini-button" type="button" disabled>停用</button>
                  <button class="mini-button danger" type="button" disabled>删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.stores-page {
  display: grid;
  gap: 18px;
}

.stores-title {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.stores-title h3 {
  margin: 0 0 3px;
  font-size: 18px;
}

.store-toolbar,
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.row-actions {
  justify-content: flex-start;
}

.mini-button.danger {
  color: var(--bad);
}

@media (max-width: 720px) {
  .store-toolbar,
  .store-toolbar button {
    width: 100%;
  }
}
</style>
