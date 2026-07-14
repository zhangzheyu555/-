<script setup lang="ts">
import StoreSalaryWorkbench from '../components/salary/StoreSalaryWorkbench.vue'
</script>

<template>
  <section class="page-panel salary-page">
    <StoreSalaryWorkbench />
  </section>
</template>

<style scoped>
.salary-page {
  display: grid;
  gap: 0;
  width: 100%;
  max-width: none;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
</style>
