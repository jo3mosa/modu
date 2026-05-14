# ============================================================
# Stage 1: Build
# Node.js 환경에서 React 앱을 정적 파일(dist/)로 빌드합니다.
# ============================================================
FROM node:25.2.1-alpine AS builder

# 작업 디렉토리 설정
WORKDIR /app

# package.json과 lock 파일만 먼저 복사
# (소스코드 변경 시 npm install 레이어 캐시 재사용)
COPY package*.json ./

# 정확한 버전으로 의존성 설치 (npm install 대신 ci 사용)
RUN npm ci

# 소스코드 전체 복사 후 프로덕션 빌드 실행
COPY . .
ARG VITE_KAKAO_CLIENT_ID
RUN echo "VITE_KAKAO_CLIENT_ID=${VITE_KAKAO_CLIENT_ID}" > .env.production
RUN npm run build

# ============================================================
# Stage 2: Serve
# Nginx로 빌드된 정적 파일을 서빙합니다.
# Node.js 없이 Nginx만 사용해 이미지 크기를 최소화합니다.
# K8s frontend.yaml의 containerPort: 80과 일치합니다.
# ============================================================
FROM nginx:alpine

# 빌드된 정적 파일을 Nginx 서빙 디렉토리로 복사
COPY --from=builder /app/dist /usr/share/nginx/html

# Nginx 설정 파일 선택 (환경별 분기)
#   - docker-compose 환경: nginx.conf (기본값) — /api/, /ws/ proxy 포함
#   - K8s 환경:           nginx.k8s.conf      — Ingress 가 proxy 처리하므로 정적 서빙만
# 빌드 시: docker build --build-arg NGINX_CONF=nginx.k8s.conf ...
ARG NGINX_CONF=nginx.conf
COPY ${NGINX_CONF} /etc/nginx/conf.d/default.conf

# Nginx 기본 포트
EXPOSE 80

# Nginx를 포그라운드 모드로 실행 (컨테이너 유지용)
CMD ["nginx", "-g", "daemon off;"]