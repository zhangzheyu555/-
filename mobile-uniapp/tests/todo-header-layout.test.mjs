import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const todoPageSource = readFileSync(
  new URL('../src/pages/todo/index.vue', import.meta.url),
  'utf8',
)
const template = todoPageSource.match(/<template>([\s\S]*?)<\/template>/)?.[1] || ''

test('今日待办页移除顶部操作栏并在标题右侧展示品牌标识', () => {
  assert.doesNotMatch(template, /class="todo-topbar"/)
  assert.doesNotMatch(template, /class="refresh-button"/)
  assert.match(
    template,
    /<view class="todo-heading">\s*<view class="todo-heading__copy">[\s\S]*?今日待办[\s\S]*?<\/view>\s*<BrandLockup compact \/>\s*<\/view>/,
  )
})
