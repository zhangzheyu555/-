import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/components/BossHomeDashboard.vue', import.meta.url), 'utf8')
const tabsDeclaration = source.match(/const sectionTabs = computed\(\(\) => \[.*?\]\)/s)?.[0] || ''
const overviewCards = source.match(/<view class="kpi-grid">.*?<\/view>\n\n[ ]{4}<view class="focus-card">/s)?.[0] || ''

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

expect(tabsDeclaration.includes("label: '需要我处理'"), '老板首页应保留“需要我处理”分类')
expect(tabsDeclaration.includes("label: '岗位进度'"), '老板首页应保留“岗位进度”分类')
expect(!tabsDeclaration.includes("label: '待复核'"), '老板首页不应显示“待复核”分类')
expect(!tabsDeclaration.includes("label: '培训考试'"), '老板首页不应显示“培训考试”分类')
expect(!tabsDeclaration.includes("label: '风险门店'"), '老板首页不应显示“风险门店”分类')
expect(!tabsDeclaration.includes("label: '已完成'"), '老板首页不应显示“已完成”分类')
expect(overviewCards.includes('营业额'), '老板首页应保留营业额概览')
expect(overviewCards.includes('净利润'), '老板首页应保留净利润概览')
expect(!overviewCards.includes('待复核'), '老板首页概览不应显示“待复核”')
expect(!overviewCards.includes('风险门店'), '老板首页概览不应显示“风险门店”')
