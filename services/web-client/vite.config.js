import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * Vite configuration for the React web client.
 *
 * The dev-server proxy forwards /api calls to the Spring Boot server. It is
 * the local server by default; set API_TARGET to use another one, e.g. the
 * production Cloud Run URL to check the UI against current data:
 *
 *   $env:API_TARGET = "https://transit-api-....run.app"; npm run dev
 *
 * Only affects `npm run dev`. The deployed site reaches the API through the
 * Firebase Hosting rewrite.
 */
const apiTarget = process.env.API_TARGET || "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: apiTarget,
        changeOrigin: true,
        secure: true
      }
    }
  }
});
