import { cpSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const outDir = fileURLToPath(new URL('../app/src/main/assets/ebook', import.meta.url))

/**
 * Builds the Android app's ebook reader page (src/ebook-android) into the app's assets.
 * Run `npm run build:android-reader` after changing it or foliate-js, and commit the output.
 */
export default defineConfig({
  root: fileURLToPath(new URL('./src/ebook-android', import.meta.url)),
  base: './',
  publicDir: false,
  plugins: [
    {
      // PDF.js's worker, fonts and character maps, next to the page (FEEDER_PDFJS_BASE = ./pdfjs/)
      name: 'copy-pdfjs',
      closeBundle() {
        cpSync(fileURLToPath(new URL('./public/pdfjs', import.meta.url)), `${outDir}/pdfjs`, { recursive: true })
      },
    },
  ],
  build: {
    outDir,
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
