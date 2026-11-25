import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

/**
 * Builds the Android app's ebook reader page (src/ebook-android) into the app's assets.
 * Run `npm run build:android-reader` after changing it or foliate-js, and commit the output.
 */
export default defineConfig({
  root: fileURLToPath(new URL('./src/ebook-android', import.meta.url)),
  base: './',
  publicDir: false,
  build: {
    outDir: fileURLToPath(new URL('../app/src/main/assets/ebook', import.meta.url)),
    emptyOutDir: true,
    target: 'chrome100',
    sourcemap: false,
    // Fixed names keep diffs small when it is rebuilt
    rolldownOptions: {
      output: {
        entryFileNames: 'reader.js',
        chunkFileNames: '[name].js',
        assetFileNames: '[name][extname]',
      },
    },
  },
})
