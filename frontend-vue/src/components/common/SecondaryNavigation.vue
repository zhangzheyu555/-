<script setup lang="ts">
import type { Component } from 'vue'
import type { RouteLocationRaw } from 'vue-router'

export interface SecondaryNavigationItem {
  key: string
  label: string
  badge?: number
  icon?: Component
  to?: RouteLocationRaw
}

withDefaults(defineProps<{
  items: SecondaryNavigationItem[]
  modelValue: string
  label: string
}>(), {})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <nav class="secondary-navigation" :aria-label="label">
    <template v-for="item in items" :key="item.key">
      <RouterLink v-if="item.to" v-slot="{ href, navigate }" custom :to="item.to">
        <a
          class="secondary-navigation__item"
          :class="{ 'secondary-navigation__item--active': modelValue === item.key }"
          :href="href"
          :aria-current="modelValue === item.key ? 'location' : undefined"
          @click="navigate"
        >
          <slot name="icon" :item="item" />
          <span>{{ item.label }}</span>
          <b v-if="item.badge" class="secondary-navigation__badge">{{ item.badge }}</b>
        </a>
      </RouterLink>
      <button
        v-else
        class="secondary-navigation__item"
        :class="{ 'secondary-navigation__item--active': modelValue === item.key }"
        type="button"
        :aria-pressed="modelValue === item.key"
        @click="emit('update:modelValue', item.key)"
      >
        <slot name="icon" :item="item" />
        <span>{{ item.label }}</span>
        <b v-if="item.badge" class="secondary-navigation__badge">{{ item.badge }}</b>
      </button>
    </template>
  </nav>
</template>

<style scoped>
.secondary-navigation {
  display: flex;
  max-width: 100%;
  gap: 2px;
  padding: 4px;
  overflow-x: auto;
  border: 1px solid #dfe7e9;
  border-radius: 8px;
  background: #f4f7f8;
  scrollbar-width: none;
}

.secondary-navigation::-webkit-scrollbar {
  display: none;
}

.secondary-navigation__item {
  display: inline-flex;
  min-height: 36px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 6px 13px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #667483;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
  text-decoration: none;
}

.secondary-navigation__item:hover {
  background: #eaf0f1;
  color: var(--primary-dark);
}

.secondary-navigation__item:focus-visible {
  outline: 2px solid rgba(39, 107, 101, 0.28);
  outline-offset: -2px;
}

.secondary-navigation__item--active {
  background: #fff;
  color: var(--primary-dark);
  box-shadow: 0 1px 4px rgba(19, 35, 42, 0.1);
}

.secondary-navigation__badge {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

@media (max-width: 768px) {
  .secondary-navigation {
    scroll-snap-type: x proximity;
  }

  .secondary-navigation__item {
    min-height: 44px;
    scroll-snap-align: start;
  }
}
</style>
