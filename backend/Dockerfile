# ============================================================
# Stage 1: Build
# JDK가 포함된 이미지에서 Gradle로 JAR 파일을 빌드합니다.
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

# 작업 디렉토리 설정
WORKDIR /app

# Gradle Wrapper 및 의존성 설정 파일 먼저 복사
# (소스코드 변경과 무관한 레이어를 캐시하여 빌드 속도 향상)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

# 의존성 다운로드 (소스코드 변경 시에도 이 레이어는 캐시 재사용)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon

# 소스코드 복사 및 JAR 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# ============================================================
# Stage 2: Run
# 실행에 필요한 JRE만 포함한 경량 이미지로 JAR를 실행합니다.
# JDK 없이 JRE만 사용해 이미지 크기를 최소화합니다.
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# 작업 디렉토리 설정
WORKDIR /app

# 빌드 스테이지에서 생성된 JAR 파일만 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# Spring Boot 기본 포트
EXPOSE 8080

# 컨테이너 시작 시 JAR 실행
ENTRYPOINT ["java", "-jar", "app.jar"]