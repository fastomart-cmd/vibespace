import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globalSetup: ['./tests/global-setup.ts'],
    fileParallelism: false, // files share one real Postgres database
    testTimeout: 30000,
    hookTimeout: 60000,
  },
});
