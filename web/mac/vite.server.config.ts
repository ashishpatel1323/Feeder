import { defineConfig } from 'vite'

// Bundles the Mac app's server (and everything it imports) into one CommonJS file
export default defineConfig({
  publicDir: false,
  build: {
    ssr: 'mac/server.ts',
    outDir: 'mac/dist',
    emptyOutDir: false,
    target: 'node20',
    minify: false,
    rollupOptions: { output: { format: 'cjs', entryFileNames: 'server.cjs' } },
  },
  ssr: { noExternal: true, target: 'node' },
})
