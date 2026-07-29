import { expect, test } from '@playwright/test'

test('公共单选框在父级异步回传前立即显示刚选中的内容', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(async () => {
    const [{ createApp, h, ref }, { default: SearchableSingleSelect }] = await Promise.all([
      import('/node_modules/.vite/deps/vue.js'),
      import('/src/components/common/SearchableSingleSelect.vue'),
    ])
    document.body.innerHTML = '<main id="select-test-root"></main>'
    const selected = ref('store-a')
    createApp({
      setup() {
        return () => h(SearchableSingleSelect, {
          modelValue: selected.value,
          options: [
            { value: 'store-a', label: '门店 A' },
            { value: 'store-b', label: '门店 B' },
          ],
          ariaLabel: '测试门店',
          'onUpdate:modelValue': (value: string | number) => {
            window.setTimeout(() => {
              selected.value = String(value)
            }, 800)
          },
        })
      },
    }).mount('#select-test-root')
  })

  const select = page.getByRole('combobox', { name: '测试门店' })
  await expect(select).toHaveValue('门店 A')
  await select.click()
  await page.getByRole('option', { name: '门店 B' }).click()

  expect(await select.inputValue()).toBe('门店 B')
})

test('公共多选框在父级异步回传前立即勾选并更新数量', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(async () => {
    const [{ createApp, h, ref }, { default: SearchableMultiSelect }] = await Promise.all([
      import('/node_modules/.vite/deps/vue.js'),
      import('/src/components/common/SearchableMultiSelect.vue'),
    ])
    document.body.innerHTML = '<main id="select-test-root"></main>'
    const selected = ref<string[]>([])
    createApp({
      setup() {
        return () => h(SearchableMultiSelect, {
          modelValue: selected.value,
          options: [
            { value: 'store-a', label: '门店 A' },
            { value: 'store-b', label: '门店 B' },
          ],
          ariaLabel: '测试门店多选',
          compact: true,
          selectedNoun: '家门店',
          'onUpdate:modelValue': (value: Array<string | number>) => {
            window.setTimeout(() => {
              selected.value = value.map(String)
            }, 3_000)
          },
        })
      },
    }).mount('#select-test-root')
  })

  const trigger = page.getByRole('button', { name: /测试门店多选/ })
  await trigger.click()
  const storeB = page.getByRole('checkbox', { name: '门店 B' })
  await storeB.click()

  expect(await storeB.isChecked()).toBe(true)
  await expect(trigger).toContainText('已选择 1 家门店', { timeout: 400 })
})
