<script setup lang="ts">
import ActionConfirmDialog from './ActionConfirmDialog.vue'
import {
  confirmAppErrorDialog,
  currentAppErrorDialog,
  dismissAppErrorDialog,
} from '../../errors/appErrorDialog'
</script>

<template>
  <ActionConfirmDialog
    :open="Boolean(currentAppErrorDialog)"
    :title="currentAppErrorDialog?.title || '操作未完成'"
    :message="currentAppErrorDialog?.message || ''"
    :confirm-label="currentAppErrorDialog?.actionLabel || '我知道了'"
    confirm-variant="danger"
    confirm-autofocus
    acknowledge-only
    @cancel="dismissAppErrorDialog"
    @confirm="confirmAppErrorDialog"
  />
</template>
