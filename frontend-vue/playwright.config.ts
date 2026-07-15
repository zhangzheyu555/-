import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results/artifacts',
  timeout: 30_000,
  expect: {
    timeout: 8_000,
  },
  fullyParallel: false,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'test-results/html-report', open: 'never' }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    // Keep the existing project id for CI and local command compatibility.
    // It is the Desktop Chrome release profile.
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'iphone-390',
      use: { ...devices['iPhone 13'] },
    },
    {
      name: 'android-412',
      use: { ...devices['Pixel 7'] },
    },
    {
      name: 'ipad-768',
      use: { ...devices['iPad Mini'] },
    },
  ],
})
