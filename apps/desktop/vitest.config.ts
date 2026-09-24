import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    // scripts/ holds Phase16's measurement tooling; its pure core
    // (lib/sessionStatsSummary.mjs) is covered like product code.
    include: ['src/**/*.test.ts', 'scripts/**/*.test.ts']
  }
})
