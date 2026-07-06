<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="selectedBrand">
        <option value="全部品牌">全部品牌</option>
        <option v-for="brand in brandNames" :key="brand">{{ brand }}</option>
      </select>
      <select v-model="selectedStatus">
        <option value="全部状态">全部状态</option>
        <option v-for="status in statusOptions" :key="status">{{ status }}</option>
      </select>
      <button class="primary-button" @click="openCreate">
        <Building2 />
        新增门店
      </button>
      <button class="ghost-button" @click="loadData">
        <RefreshCw />
        刷新
      </button>
    </div>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>门店档案</h2>
          <span>后端实时读取，受当前账号权限范围控制</span>
        </div>
        <span>{{ loading ? '加载中' : `${visibleRows.length} 家` }}</span>
      </div>
      <div v-if="error" class="inline-alert">{{ error }}</div>
      <DataTable :columns="columns" :rows="visibleRows" row-key="id">
        <template #name="{ row }">
          <strong>{{ row.name }}</strong>
          <small>{{ row.code }}</small>
        </template>
        <template #brandName="{ row }">
          <span class="brand-pill">{{ row.brandName }}</span>
        </template>
        <template #status="{ row }">
          <StatusTag :label="String(row.status)" :tone="statusTone(String(row.status))" />
        </template>
        <template #openDate="{ row }">
          {{ row.openDate || '-' }}
        </template>
        <template #actions="{ row }">
          <button class="table-action" @click="openEditor(row)">编辑</button>
        </template>
      </DataTable>
    </section>

    <div v-if="editorOpen" class="modal-backdrop" @click.self="closeEditor">
      <form class="modal-panel" @submit.prevent="submitStore">
        <div class="panel-head">
          <h2>{{ editorMode === 'create' ? '新增门店' : '编辑门店' }}</h2>
          <button type="button" class="icon-button" title="关闭" @click="closeEditor">
            <X />
          </button>
        </div>

        <div class="form-grid">
          <label>
            <span>门店 ID</span>
            <input v-model.trim="form.id" :disabled="editorMode === 'update'" placeholder="例如 rg1" />
          </label>
          <label>
            <span>门店编码</span>
            <input v-model.trim="form.code" placeholder="例如 RG001" />
          </label>
          <label>
            <span>门店名称</span>
            <input v-model.trim="form.name" placeholder="请输入门店名称" />
          </label>
          <label>
            <span>品牌</span>
            <select v-model.number="form.brandId">
              <option v-for="brand in brands" :key="brand.id" :value="brand.id">{{ brand.name }}</option>
            </select>
          </label>
          <label>
            <span>区域</span>
            <input v-model.trim="form.area" placeholder="请输入区域" />
          </label>
          <label>
            <span>负责人</span>
            <input v-model.trim="form.manager" placeholder="请输入负责人" />
          </label>
          <label>
            <span>开业日期</span>
            <input v-model="form.openDate" type="date" />
          </label>
          <label>
            <span>状态</span>
            <select v-model="form.status">
              <option v-for="status in statusOptions" :key="status">{{ status }}</option>
            </select>
          </label>
          <label class="span-2">
            <span>备注</span>
            <input v-model.trim="form.note" placeholder="可填写账号、租约或运营备注" />
          </label>
        </div>

        <div v-if="formError" class="inline-alert">{{ formError }}</div>

        <div class="modal-actions">
          <button type="button" class="ghost-button" @click="closeEditor">取消</button>
          <button class="primary-button" :disabled="saving">
            <Save />
            {{ saving ? '保存中' : '保存' }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Building2, RefreshCw, Save, X } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import StatusTag from '../components/StatusTag.vue';
import {
  fetchBrands,
  fetchStores,
  saveStore,
  type BrandRecord,
  type StoreRecord,
  type StoreUpsertPayload
} from '../services/api';

const statusOptions = ['营业中', '待开业', '已停业'];
const selectedBrand = ref('全部品牌');
const selectedStatus = ref('全部状态');
const brands = ref<BrandRecord[]>([]);
const stores = ref<StoreRecord[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const formError = ref('');
const editorOpen = ref(false);
const editorMode = ref<'create' | 'update'>('create');
const form = ref<StoreUpsertPayload>(emptyForm());

const brandNames = computed(() => brands.value.map((brand) => brand.name));
const visibleRows = computed(() => stores.value
  .filter((store) => selectedBrand.value === '全部品牌' || store.brandName === selectedBrand.value)
  .filter((store) => selectedStatus.value === '全部状态' || store.status === selectedStatus.value)
  .map((store) => ({
    ...store,
    openDate: store.openDate || '-',
    manager: store.manager || '-',
    area: store.area || '-'
  })) as unknown as Record<string, unknown>[]);

const columns: TableColumn[] = [
  { key: 'name', label: '门店' },
  { key: 'brandName', label: '品牌' },
  { key: 'area', label: '区域' },
  { key: 'manager', label: '负责人' },
  { key: 'openDate', label: '开业日期' },
  { key: 'status', label: '状态' },
  { key: 'actions', label: '操作', align: 'right' }
];

onMounted(loadData);

async function loadData() {
  loading.value = true;
  error.value = '';
  try {
    const [brandRecords, storeRecords] = await Promise.all([fetchBrands(), fetchStores()]);
    brands.value = brandRecords;
    stores.value = storeRecords;
    if (!brandNames.value.includes(selectedBrand.value)) {
      selectedBrand.value = '全部品牌';
    }
  } catch {
    error.value = '门店数据加载失败，请确认后端服务和登录状态正常。';
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editorMode.value = 'create';
  form.value = emptyForm();
  form.value.brandId = brands.value[0]?.id ?? 0;
  formError.value = '';
  editorOpen.value = true;
}

function openEditor(row: Record<string, unknown>) {
  const store = stores.value.find((item) => item.id === String(row.id));
  if (!store) {
    return;
  }
  editorMode.value = 'update';
  form.value = {
    id: store.id,
    code: store.code || '',
    name: store.name,
    brandId: store.brandId,
    area: store.area || '',
    manager: store.manager || '',
    openDate: store.openDate || '',
    status: store.status || '营业中',
    note: store.note || ''
  };
  formError.value = '';
  editorOpen.value = true;
}

function closeEditor() {
  editorOpen.value = false;
}

async function submitStore() {
  formError.value = '';
  if (!form.value.id || !form.value.name || !form.value.brandId) {
    formError.value = '门店 ID、门店名称和品牌不能为空。';
    return;
  }
  saving.value = true;
  try {
    await saveStore(form.value, editorMode.value);
    editorOpen.value = false;
    await loadData();
  } catch {
    formError.value = '保存失败，请检查门店信息或当前账号权限。';
  } finally {
    saving.value = false;
  }
}

function emptyForm(): StoreUpsertPayload {
  return {
    id: '',
    code: '',
    name: '',
    brandId: 0,
    area: '',
    manager: '',
    openDate: '',
    status: '营业中',
    note: ''
  };
}

function statusTone(status: string): 'good' | 'warn' | 'neutral' {
  if (status === '营业中') {
    return 'good';
  }
  if (status === '待开业') {
    return 'warn';
  }
  return 'neutral';
}
</script>
