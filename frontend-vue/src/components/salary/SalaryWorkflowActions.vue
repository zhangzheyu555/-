<script setup lang="ts">
import { Check, Edit3, Lock, Send, Trash2, XCircle } from 'lucide-vue-next'
import type { SalaryRecord } from '../../api/finance'
import { isEditable } from '../../composables/useSalaryPage'

defineProps<{
  record: SalaryRecord
  canEdit: boolean
  canReview: boolean
  actioningId: string
  deletingId: string
  storeName: string
}>()

const emit = defineEmits<{
  view: [record: SalaryRecord]
  edit: [record: SalaryRecord]
  submit: [record: SalaryRecord]
  approve: [record: SalaryRecord]
  reject: [record: SalaryRecord]
  delete: [record: SalaryRecord]
  markPaid: [record: SalaryRecord]
  lock: [record: SalaryRecord]
}>()
</script>

<template>
  <td class="r action-cell" @click.stop>
    <button class="mini-button" @click="emit('view', record)">工资明细</button>
    <button v-if="canEdit && isEditable(record.status)" class="icon-button" title="编辑" @click="emit('edit', record)"><Edit3 :size="14" /></button>
    <button v-if="canEdit && isEditable(record.status)" class="mini-button primary" :disabled="actioningId === record.id" @click="emit('submit', record)">提交<Send :size="12" /></button>
    <button v-if="canReview && (record.status === 'SUBMITTED' || record.status === 'PENDING_REVIEW')" class="mini-button primary" :disabled="actioningId === record.id" @click="emit('approve', record)"><Check :size="14" /></button>
    <button v-if="canReview && (record.status === 'SUBMITTED' || record.status === 'PENDING_REVIEW')" class="mini-button" :disabled="actioningId === record.id" @click="emit('reject', record)"><XCircle :size="14" /></button>
    <button v-if="canEdit && record.status === 'APPROVED'" class="mini-button" :disabled="actioningId === record.id" @click="emit('markPaid', record)">发放</button>
    <button v-if="canEdit && (record.status === 'APPROVED' || record.status === 'PAID')" class="icon-button" title="锁定" :disabled="actioningId === record.id" @click="emit('lock', record)"><Lock :size="13" /></button>
    <button v-if="canEdit && isEditable(record.status)" class="icon-button danger" title="删除" :disabled="deletingId === record.id" @click="emit('delete', record)"><Trash2 :size="14" /></button>
  </td>
</template>

<style scoped>
.action-cell { position: sticky; right: 0; z-index: 2; display: flex; justify-content: flex-end; gap: 5px; white-space: nowrap; background: #fff; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--muted); cursor: pointer; }
.icon-button:hover { border-color: rgba(118,189,184,0.34); color: var(--primary-dark); }
.icon-button.danger:hover { border-color: rgba(231,76,60,0.32); color: var(--bad); }
</style>
