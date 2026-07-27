import { ESLint } from 'eslint'

const eslint = new ESLint({ cwd: new URL('..', import.meta.url).pathname })
const results = await eslint.lintFiles([
  'src/components/ProtectedAttachmentList.vue',
  'src/pages/inspection/index.vue',
  'src/pkg-boss/employees/index.vue',
  'src/pkg-boss/stores/index.vue',
  'src/pkg-boss/users/index.vue',
  'src/pkg-warehouse/operations/index.vue',
])
const problems = results.flatMap((result) => result.messages.map((message) => `${result.filePath}:${message.line}:${message.column} ${message.message}`))

if (problems.length) throw new Error(`目标文件仍有 ESLint 问题：\n${problems.join('\n')}`)
