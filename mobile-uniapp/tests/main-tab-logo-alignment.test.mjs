import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const readSource = (relativePath) => readFileSync(
  new URL(relativePath, import.meta.url),
  'utf8',
)

const sources = [
  ['普通工作台', readSource('../src/pages/home/index.vue'), '.workspace-head'],
  ['老板工作台', readSource('../src/components/BossHomeDashboard.vue'), '.boss-topbar'],
  ['待办', readSource('../src/pages/todo/index.vue'), '.todo-heading'],
  ['应用', readSource('../src/components/PageHeader.vue'), '.page-header'],
  ['我的', readSource('../src/pages/profile/index.vue'), '.profile-hero'],
]

test('四个主页面的品牌标识统一从内容区顶部对齐', () => {
  for (const [name, source, selector] of sources) {
    const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const rule = source.match(new RegExp(`${escapedSelector}\\s*\\{([^}]+)\\}`))?.[1] || ''

    assert.match(
      rule,
      /align-items:\s*flex-start/,
      `${name}的品牌标识应从标题区域顶部对齐`,
    )

    if (name === '老板工作台') {
      assert.doesNotMatch(
        rule,
        /padding-top:/,
        '老板工作台不应额外下移品牌标识',
      )
    }
  }
})
