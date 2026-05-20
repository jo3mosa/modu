# syntax=docker/dockerfile:1
# ============================================================
# Stage 1: Build
# JDK가 포함된 이미지에서 Gradle로 JAR 파일을 빌드합니다.
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

# 작업 디렉토리 설정
WORKDIR /app

# Gradle Wrapper 및 의존성 설정 파일 먼저 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x ./gradlew

# 소스코드 복사 및 JAR 빌드
# --mount=type=cache : Gradle 의존성/래퍼(~/.gradle)를 빌드 간에 영속 캐시.
#   레이어 캐시가 깨지거나(다른 브랜치·최초 빌드) build.gradle.kts 가 바뀌어도
#   이미 받은 의존성은 재다운로드하지 않아 빌드가 빨라진다. (BUILDKIT 필요)
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# ============================================================
# Stage 2: Run
# 실행에 필요한 JRE만 포함한 경량 이미지로 JAR를 실행합니다.
# JDK 없이 JRE만 사용해 이미지 크기를 최소화합니다.
#
# [base 이미지 선택]
#   alpine 변형은 musl libc 라 Kafka Snappy native 라이브러리(glibc 빌드)가
#   초기화 실패 → "Could not initialize class org.xerial.snappy.Snappy" 발생.
#   Debian 기반 jre 이미지로 두어 Snappy/lz4 등 native 라이브러리 호환성 확보.
# ============================================================
FROM eclipse-temurin:21-jre

# 작업 디렉토리 설정
WORKDIR /app

# 빌드 스테이지에서 생성된 JAR 파일만 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# Spring Boot 기본 포트
EXPOSE 8080

# 컨테이너 시작 시 JAR 실행
ENTRYPOINT ["java", "-jar", "app.jar"]