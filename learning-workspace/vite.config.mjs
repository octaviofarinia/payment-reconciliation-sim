import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: process.env.VITE_BASE_PATH || "/",
  build: {
    outDir: "dist/client",
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules') && (id.includes('@xyflow') || id.includes('/d3-') || id.includes('/zustand/'))) return 'data-explorer';
          if (id.includes('node_modules') && (id.includes('/react/') || id.includes('/react-dom/') || id.includes('/scheduler/'))) return 'react-runtime';
        },
      },
    },
  },
  optimizeDeps: {
    include: ["react", "react-dom/client"],
  },
  server: {
    host: "0.0.0.0",
    allowedHosts: ["terminal.local"],
    warmup: {
      clientFiles: ["./src/main.jsx"],
    },
  },
  plugins: [react()],
});
