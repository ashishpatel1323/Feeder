import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

/**
 * Builds the script the Android app injects into ZIM pages (src/zim-android) into the app's assets,
 * as one self-contained file. Run `npm run build:android-zim` after changing it or lib/zim/marks.ts,
 * and commit the output.
 */
export default defineConfig({
  publicDir: false,
  build: {
    outDir: fileURLToPath(new URL('../app/src/main/assets/zim', import.meta.url)),
    emptyOutDir: true,
    target: 'chrome100',
    sourcemap: false,
    lib: {
      entry: fileURLToPath(new URL('./src/zim-android/main.ts', import.meta.url)),
      formats: ['iife'],
      name: 'feederZimScript',
      fileName: () => 'feeder-zim.js',
    },
  },
})
