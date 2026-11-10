import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  return {
    plugins: [react(), tailwindcss()],
    server: {
      // Dev qua tunnel (ngrok/cloudflared): Vite 6 chặn Host lạ. Mặc định chỉ mở cho domain tunnel,
      // override bằng VITE_ALLOWED_HOSTS (danh sách phân tách bằng dấu phẩy).
      allowedHosts: (env.VITE_ALLOWED_HOSTS ?? ".ngrok-free.dev,.ngrok-free.app,.trycloudflare.com")
        .split(",")
        .map((host) => host.trim())
        .filter(Boolean),
      proxy: {
        "/api": {
          target: env.VITE_API_PROXY_TARGET || "http://localhost:8080",
          changeOrigin: true,
        },
      },
    },
  };
});
