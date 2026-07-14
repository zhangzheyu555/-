<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ChevronDown, FolderPlus, FolderTree, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import type { WarehouseItem, WarehouseItemCategory } from '../../api/warehouse'
import ActionConfirmDialog from '../ui/ActionConfirmDialog.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const props = withDefaults(defineProps<{
  categories: WarehouseItemCategory[]
  items: WarehouseItem[]
  selected: string
  canManage?: boolean
  actioningId?: string
}>(), {
  canManage: false,
  actioningId: '',
})

const emit = defineEmits<{
  select: [value: string]
  save: [payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }]
  remove: [id: number]
}>()

interface CategoryRow {
  id?: number
  name: string
  count: number
  depth: number
  enabled?: boolean
}

const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editorSnapshot = ref('')
const showDiscardConfirmation = ref(false)
const pendingDelete = ref<CategoryRow | null>(null)
const editor = reactive({
  id: undefined as number | undefined,
  name: '',
  parentId: null as number | null,
  sortOrder: 0,
  enabled: true,
})

function flatten(categories: WarehouseItemCategory[], depth = 0): CategoryRow[] {
  return categories.flatMap((category) => [
    {
      id: category.id,
      name: category.name,
      count: props.items.filter((item) => item.categoryId === category.id).length,
      depth,
      enabled: category.enabled,
    },
    ...flatten(category.children || [], depth + 1),
  ])
}

const rows = computed<CategoryRow[]>(() => flatten(props.categories))
const parentOptions = computed(() => rows.value.filter((row) => row.id !== editor.id))
const editorDirty = computed(() => editorOpen.value && JSON.stringify(editor) !== editorSnapshot.value)

function captureEditor() {
  editorSnapshot.value = JSON.stringify(editor)
}

function openCreate(parentId: number | null = null) {
  editorMode.value = 'create'
  editor.id = undefined
  editor.name = ''
  editor.parentId = parentId
  editor.sortOrder = 0
  editor.enabled = true
  editorOpen.value = true
  captureEditor()
}

function openEdit(row: CategoryRow) {
  if (!row.id) return
  const source = findCategory(row.id, props.categories)
  if (!source) return
  editorMode.value = 'edit'
  editor.id = source.id
  editor.name = source.name
  editor.parentId = source.parentId || null
  editor.sortOrder = source.sortOrder
  editor.enabled = source.enabled
  editorOpen.value = true
  captureEditor()
}

function findCategory(id: number, categories: WarehouseItemCategory[]): WarehouseItemCategory | null {
  for (const category of categories) {
    if (category.id === id) return category
    const child = findCategory(id, category.children || [])
    if (child) return child
  }
  return null
}

function save() {
  if (!editor.name.trim()) return
  emit('save', {
    id: editor.id,
    name: editor.name.trim(),
    parentId: editor.parentId,
    sortOrder: Number(editor.sortOrder || 0),
    enabled: editor.enabled,
  })
  editorOpen.value = false
  editorSnapshot.value = ''
}

function remove(row: CategoryRow) {
  if (row.id) pendingDelete.value = row
}

function confirmRemove() {
  if (!pendingDelete.value?.id) return
  emit('remove', pendingDelete.value.id)
  pendingDelete.value = null
}

function requestCancel() {
  if (editorDirty.value) showDiscardConfirmation.value = true
  else closeEditor()
}

function closeEditor() {
  showDiscardConfirmation.value = false
  editorOpen.value = false
  editorSnapshot.value = ''
}
</script>

<template>
  <aside class="warehouse-category-tree" aria-label="物料分类">
    <div class="category-tree-head">
      <div>
        <FolderTree :size="18" />
        <h3>物料分类</h3>
      </div>
      <button v-if="canManage" class="icon-button" type="button" title="新增分类" aria-label="新增物料分类" @click="openCreate()">
        <Plus :size="17" />
      </button>
    </div>

    <button class="category-row all" :class="{ active: selected === 'all' }" type="button" @click="emit('select', 'all')">
      <ChevronDown :size="16" />
      <span>全部类别</span>
      <b>{{ items.length }}</b>
    </button>

    <div v-if="rows.length" class="category-list">
      <div v-for="row in rows" :key="row.id" class="category-line" :class="{ disabled: row.enabled === false }">
        <button
          class="category-row"
          :class="{ active: selected === `id:${row.id}` }"
          type="button"
          :style="{ paddingLeft: `${18 + row.depth * 16}px` }"
          @click="emit('select', `id:${row.id}`)"
        >
          <span>{{ row.name }}</span>
          <b>{{ row.count }}</b>
        </button>
        <div v-if="canManage" class="category-tools">
          <button class="icon-button small" type="button" title="新增下级分类" :aria-label="`为${row.name}新增下级分类`" @click="openCreate(row.id || null)">
            <FolderPlus :size="14" />
          </button>
          <button class="icon-button small" type="button" title="编辑分类" :aria-label="`编辑分类${row.name}`" @click="openEdit(row)">
            <Pencil :size="14" />
          </button>
          <button class="icon-button small danger" type="button" title="删除分类" :aria-label="`删除分类${row.name}`" :disabled="actioningId === `category-delete:${row.id}`" @click="remove(row)">
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </div>
    <div v-else class="tree-empty">暂无分类</div>

    <div v-if="editorOpen" class="category-editor">
      <div class="category-editor-title">{{ editorMode === 'create' ? '新增分类' : '编辑分类' }}</div>
      <label>
        分类名称
        <input v-model="editor.name" maxlength="120" placeholder="例如：水果" />
      </label>
      <label>
        上级分类
        <select v-model="editor.parentId">
          <option :value="null">顶级分类</option>
          <option v-for="row in parentOptions" :key="row.id" :value="row.id">{{ row.name }}</option>
        </select>
      </label>
      <label>
        排序
        <input v-model.number="editor.sortOrder" type="number" min="0" step="1" />
      </label>
      <label class="category-enabled">
        <input v-model="editor.enabled" type="checkbox" />
        启用分类
      </label>
      <div class="category-editor-actions">
        <UiButton variant="secondary" @click="requestCancel">取消</UiButton>
        <UiButton variant="primary" :disabled="!editor.name.trim()" @click="save">保存</UiButton>
      </div>
    </div>

    <UnsavedChangesDialog
      :open="showDiscardConfirmation"
      title="分类修改尚未保存"
      message="关闭后，本次分类修改将不会保留。"
      @keep-editing="showDiscardConfirmation = false"
      @discard="closeEditor"
    />
    <ActionConfirmDialog
      :open="Boolean(pendingDelete)"
      title="确认删除物料分类？"
      :message="pendingDelete ? `删除“${pendingDelete.name}”后无法恢复。` : ''"
      cancel-label="取消"
      confirm-label="确认删除"
      confirm-variant="danger"
      @cancel="pendingDelete = null"
      @confirm="confirmRemove"
    />
  </aside>
</template>

<style scoped>
.warehouse-category-tree {
  align-self: start;
  overflow: hidden;
  border: 1px solid #e2edf0;
  background: #fff;
}

.category-tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 0 12px;
  border-bottom: 1px solid #e1eef1;
  background: #effafd;
}

.category-tree-head > div,
.category-row,
.category-line,
.category-tools,
.category-enabled,
.category-editor-actions {
  display: flex;
  align-items: center;
}

.category-tree-head > div {
  gap: 8px;
  color: #21525b;
}

.category-tree-head h3 {
  margin: 0;
  font-size: 15px;
}

.category-line {
  min-height: 43px;
  border-bottom: 1px dashed #e8eef0;
}

.category-row {
  flex: 1;
  min-width: 0;
  min-height: 43px;
  gap: 8px;
  padding: 0 12px;
  border: 0;
  background: transparent;
  color: #334155;
  text-align: left;
}

.category-row:hover,
.category-row.active {
  background: #fff5ed;
  color: var(--primary-dark);
}

.category-row.all {
  border-bottom: 1px dashed #e8eef0;
  font-weight: 800;
}

.category-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-row b {
  margin-left: auto;
  color: #94a3b8;
  font-size: 12px;
}

.category-line.disabled .category-row {
  color: #a8b2bd;
}

.category-tools {
  gap: 1px;
  padding-right: 5px;
}

.icon-button {
  display: inline-grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #3c7d88;
}

.icon-button:hover {
  background: #def5f8;
}

.icon-button.small {
  width: 24px;
  height: 24px;
}

.icon-button.danger:hover {
  background: #fff0ef;
  color: var(--bad);
}

.tree-empty {
  padding: 18px 12px;
  color: var(--muted);
  font-size: 13px;
}

.category-editor {
  display: grid;
  gap: 9px;
  padding: 12px;
  border-top: 1px solid #d9ebef;
  background: #f8fcfd;
}

.category-editor-title {
  color: #21525b;
  font-weight: 800;
}

.category-editor label {
  display: grid;
  gap: 5px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.category-editor input:not([type='checkbox']),
.category-editor select {
  width: 100%;
  min-height: 32px;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 5px 8px;
  background: #fff;
  color: var(--ink);
}

.category-editor .category-enabled {
  display: flex;
  gap: 7px;
}

.category-editor-actions {
  justify-content: flex-end;
  gap: 8px;
}
</style>
