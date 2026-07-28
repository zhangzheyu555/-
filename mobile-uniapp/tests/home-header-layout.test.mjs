import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const homePageSource = readFileSync(
  new URL('../src/pages/home/index.vue', import.meta.url),
  'utf8',
)
const bossDashboardSource = readFileSync(
  new URL('../src/components/BossHomeDashboard.vue', import.meta.url),
  'utf8',
)
const template = homePageSource.match(/<template>([\s\S]*?)<\/template>/)?.[1] || ''
const bossTemplate = bossDashboardSource.match(/<template>([\s\S]*?)<\/template>/)?.[1] || ''

test('小程序首页移除刷新按钮并把日期放在品牌标识下方', () => {
  assert.doesNotMatch(template, /class="topbar"/)
  assert.doesNotMatch(template, /class="profile-link"/)
  assert.doesNotMatch(template, /class="refresh-button"/)
  assert.match(
    template,
    /<view class="workspace-head__actions">\s*<BrandLockup compact \/>\s*<view class="workspace-head__date">[\s\S]*?\{\{ todayLabel \}\}[\s\S]*?<\/view>\s*<\/view>/,
  )
})

test('老板工作台移除刷新按钮并在标题右侧显示品牌标识', () => {
  assert.match(bossDashboardSource, /import BrandLockup from '@\/components\/BrandLockup\.vue'/)
  assert.doesNotMatch(bossTemplate, /class="refresh-button"/)
  assert.match(
    bossTemplate,
    /<view class="boss-topbar">[\s\S]*?老板工作台[\s\S]*?<BrandLockup compact \/>\s*<\/view>/,
  )
})
