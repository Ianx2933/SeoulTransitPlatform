import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * Vite configuration for the React web client.
 * (React 웹 클라이언트를 위한 Vite 설정이다.)
 *
 * The proxy forwards frontend API calls to the Spring Boot server.
 * (proxy는 프론트엔드의 API 호출을 Spring Boot 서버로 전달한다.)
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true
      }
    }
  }
});
