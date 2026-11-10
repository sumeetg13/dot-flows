import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The UI is served by the host application under a configurable base path, so every URL it uses is
// relative. In development, `npm run dev` proxies API calls to a host application on localhost:8080.
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'target/classes/io/dotflows/ui',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1000,
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        rewrite: (path) => '/dotflows' + path,
      },
    },
  },
})
