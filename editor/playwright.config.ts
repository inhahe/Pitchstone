import {defineConfig, devices} from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    ...devices['Pixel 7'],
    browserName: 'chromium',
  },
})
