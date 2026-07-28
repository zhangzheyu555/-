import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

function readSource(relativePath) {
  try {
    return readFileSync(new URL(relativePath, import.meta.url), 'utf8')
  } catch {
    return ''
  }
}

const brandSource = readSource('../src/components/BrandLockup.vue')
const loginSource = readSource('../src/pages/login/index.vue')

test('登录页、首页和待办页复用同一个茹菓 ifnot 品牌组件', () => {
  assert.match(brandSource, /class="brand-lockup"/)
  assert.match(brandSource, /<text>茹<\/text>/)
  assert.match(brandSource, /茹菓\*/)
  assert.match(brandSource, /v-if="compact"[\s\S]*?ifnot\*/)
  assert.match(loginSource, /<BrandLockup \/>/)
})
