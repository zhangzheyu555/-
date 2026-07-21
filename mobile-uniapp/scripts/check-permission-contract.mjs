import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

const root = process.cwd()

function source(relative) {
  const filename = path.resolve(root, relative)
  return ts.createSourceFile(filename, fs.readFileSync(filename, 'utf8'), ts.ScriptTarget.Latest, true)
}

function initializer(relative, name) {
  let found
  source(relative).forEachChild((node) => {
    if (!ts.isVariableStatement(node)) return
    for (const declaration of node.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name) && declaration.name.text === name) found = unwrap(declaration.initializer)
    }
  })
  if (!found) throw new Error(`未找到 ${relative} 中的 ${name}`)
  return found
}

function unwrap(node) {
  while (node && (ts.isAsExpression(node) || ts.isSatisfiesExpression(node) || ts.isParenthesizedExpression(node))) node = node.expression
  return node
}

function value(node) {
  node = unwrap(node)
  if (!node) return undefined
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text
  if (ts.isIdentifier(node)) return node.text
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false
  if (ts.isArrayLiteralExpression(node)) return node.elements.map(value)
  if (ts.isObjectLiteralExpression(node)) {
    const result = {}
    for (const property of node.properties) {
      if (!ts.isPropertyAssignment(property)) continue
      const key = property.name.getText().replace(/^['"]|['"]$/g, '')
      result[key] = value(property.initializer)
    }
    return result
  }
  throw new Error(`不支持的契约表达式: ${node.getText()}`)
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const pcPermissions = Object.values(value(initializer('../frontend-vue/src/permissions/permissions.ts', 'PERMISSIONS'))).sort()
const coverage = value(initializer('src/permissions/coverage.ts', 'MOBILE_PERMISSION_COVERAGE'))
const mobilePermissions = Object.keys(coverage).sort()
assert(JSON.stringify(pcPermissions) === JSON.stringify(mobilePermissions), `移动权限覆盖与 PC 不一致\nPC 独有: ${pcPermissions.filter(x => !mobilePermissions.includes(x))}\n移动独有: ${mobilePermissions.filter(x => !pcPermissions.includes(x))}`)

const actionRules = value(initializer('src/permissions/actions.ts', 'MOBILE_ACTION_RULES'))
for (const [action, rule] of Object.entries(actionRules)) assert(mobilePermissions.includes(rule.permission), `动作 ${action} 使用了未声明权限 ${rule.permission}`)

const capabilityRules = value(initializer('src/stores/capabilities.ts', 'CAPABILITY_RULES'))
const menuRules = value(initializer('src/stores/menu.ts', 'RULES'))
const menuKeys = menuRules.map(rule => rule.key)
assert(new Set(menuKeys).size === menuKeys.length, '移动菜单 key 重复')
for (const rule of menuRules) assert(capabilityRules[rule.key], `菜单 ${rule.key} 没有能力规则`)

const pages = JSON.parse(fs.readFileSync(path.resolve(root, 'src/pages.json'), 'utf8'))
const registered = new Set(pages.pages.map(page => `/${page.path}`))
for (const pack of pages.subPackages) for (const page of pack.pages) registered.add(`/${pack.root}/${page.path}`)
for (const rule of menuRules) assert(registered.has(rule.path), `菜单路径未注册: ${rule.path}`)

const expected = {
  BOSS: menuKeys,
  FINANCE: ['summary', 'expenses', 'salary', 'businessAssistant'],
  STORE_MANAGER: ['inventory', 'requisition', 'rectification', 'expenses', 'salary', 'dailyLoss', 'businessAssistant'],
  WAREHOUSE: ['warehouse', 'dailyLoss'],
  OPERATIONS: ['inspection', 'rectification', 'dailyLoss', 'operations', 'operationsMonitor', 'businessAssistant'],
  SUPERVISOR: ['inspection', 'rectification'],
  EMPLOYEE: ['learning', 'exam', 'assistant'],
}
for (const [role, snapshot] of Object.entries(expected)) {
  const actual = menuRules.filter(({ key }) => role === 'BOSS' || capabilityRules[key].roles.includes(role)).map(({ key }) => key)
  assert(JSON.stringify(actual) === JSON.stringify(snapshot), `${role} 菜单快照变化\n期望: ${snapshot}\n实际: ${actual}`)
}

const actionSnapshots = {
  BOSS: Object.keys(actionRules).filter(action => action !== 'todo.escalate'),
  FINANCE: ['todo.resolve','todo.escalate','expense.create','expense.review','expense.supplement','salary.review','salary.pay'],
  STORE_MANAGER: ['todo.resolve','todo.escalate','expense.create','expense.supplement','dailyLoss.create'],
  WAREHOUSE: ['todo.resolve','todo.escalate','dailyLoss.review','warehouse.requisition.review','warehouse.requisition.ship','warehouse.return.review','warehouse.return.receive','warehouse.purchase','warehouse.transfer.request','warehouse.transfer.approve','warehouse.transfer.ship','warehouse.transfer.receive','warehouse.alert.configure'],
  OPERATIONS: ['todo.resolve','todo.escalate','dailyLoss.review','inventory.manage','inventory.review','employeeAssistant.handoff.claim','employeeAssistant.handoff.reply','employeeAssistant.handoff.close'],
  SUPERVISOR: ['todo.resolve','todo.escalate'],
  EMPLOYEE: [],
}
for (const [role, snapshot] of Object.entries(actionSnapshots)) {
  const actual = Object.entries(actionRules).filter(([, rule]) => {
    if (rule.bossOnly && role !== 'BOSS') return false
    if (rule.denyBoss && role === 'BOSS') return false
    return role === 'BOSS' || rule.roles.includes(role)
  }).map(([action]) => action)
  assert(JSON.stringify(actual) === JSON.stringify(snapshot), `${role} 动作快照变化\n期望: ${snapshot}\n实际: ${actual}`)
}

const accessSource = fs.readFileSync(path.resolve(root, 'src/permissions/access.ts'), 'utf8')
assert(!/SUPERVISOR[^\n]+OPERATIONS|role\s*===\s*['"]SUPERVISOR['"][^\n]+OPERATIONS/.test(accessSource), 'SUPERVISOR 不得归一化为 OPERATIONS')

console.log(`权限契约通过：${pcPermissions.length} 个权限、${Object.keys(actionRules).length} 个动作、${menuRules.length} 个菜单、7 个角色菜单/动作快照。`)
