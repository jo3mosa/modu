import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // /api 로 시작하는 모든 요청을 백엔드(8080)로 포워딩
      // 개발 서버에서 CORS 문제 없이 쿠키(accessToken, refreshToken)를 주고받기 위한 설정
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

