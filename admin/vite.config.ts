import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api/* to the local wrangler (Cloudflare Worker) instance.
// Override the API base at build/dev time with VITE_API_URL, e.g.:
//   VITE_API_URL=https://api.maxleveldetox.com/api/v1
//   VITE_API_URL=mock   -> fully client-side demo mode (no backend needed)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8787',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
