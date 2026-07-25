import { expect, test } from '@playwright/test'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { extname, join, relative } from 'node:path'

const sourceRoot = fileURLToPath(new URL('../../src', import.meta.url))
const forbiddenCopy = ['刷新', '重新加载', '重新读取']
const forbiddenIcons = ['RefreshCw', 'RefreshCcw', 'RotateCw', 'RotateCcw']

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(file)
    return ['.ts', '.vue'].includes(extname(file)) ? [file] : []
  })
}

test('正式前端不再提供手动刷新控件或刷新图标', () => {
  const violations = sourceFiles(sourceRoot).flatMap((file) => {
    const source = readFileSync(file, 'utf8')
    return source.split('\n').flatMap((line, index) => (
      [...forbiddenCopy, ...forbiddenIcons]
        .filter((forbidden) => line.includes(forbidden))
        .map((forbidden) => `${relative(sourceRoot, file)}:${index + 1}: ${forbidden}`)
    ))
  })

  expect(violations).toEqual([])
})
