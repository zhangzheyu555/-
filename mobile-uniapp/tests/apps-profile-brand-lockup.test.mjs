import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const appsSource = readFileSync(
  new URL('../src/pages/apps/index.vue', import.meta.url),
  'utf8',
)
const profileSource = readFileSync(
  new URL('../src/pages/profile/index.vue', import.meta.url),
  'utf8',
)

test('应用页在页面标题右侧显示茹菓 ifnot 品牌标识', () => {
  assert.match(appsSource, /import BrandLockup from '@\/components\/BrandLockup\.vue'/)
  assert.match(
    appsSource,
    /<PageHeader[\s\S]*?>\s*<template #action>\s*<BrandLockup compact \/>\s*<\/template>\s*<\/PageHeader>/,
  )
})

test('我的页将账号信息和品牌标识放在同一行并移除绿色底卡', () => {
  assert.match(profileSource, /import BrandLockup from '@\/components\/BrandLockup\.vue'/)
  assert.doesNotMatch(profileSource, /class="profile-brand"/)
  assert.match(
    profileSource,
    /<view class="profile-hero">\s*<view class="profile-identity">[\s\S]*?<\/view>\s*<BrandLockup compact \/>\s*<\/view>/,
  )
  const heroRule = profileSource.match(/\.profile-hero \{([^}]+)\}/)?.[1] || ''
  assert.match(heroRule, /justify-content:\s*space-between/)
  assert.doesNotMatch(heroRule, /background|border-radius|box-shadow/)
})
