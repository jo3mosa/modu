package com.modu.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.global.error.ErrorCode;
import com.modu.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 설정
 *
 * [보안 정책]
 * - CSRF 비활성화: 쿠키에 SameSite=Strict 적용으로 대체
 * - 세션 비활성화: JWT 기반 stateless 인증 사용
 * - formLogin, httpBasic 비활성화: REST API 전용
 * - JwtAuthenticationFilter를 UsernamePasswordAuthenticationFilter 앞에 등록
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 인증되지 않은 요청이 보호된 엔드포인트에 접근 시 ApiResponse 형식으로 401 반환
                // JwtAuthenticationFilter에서 설정한 authErrorCode attribute로 만료/위조 구분
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            ErrorCode errorCode = (ErrorCode) request.getAttribute("authErrorCode");
                            if (errorCode == null) {
                                errorCode = AuthErrorCode.INVALID_TOKEN;
                            }
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(
                                            ApiResponse.fail(errorCode.getDefaultMessage(), errorCode, null)
                                    )
                            );
                        })
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/social/**",  // 소셜 로그인
                                "/api/v1/auth/refresh",    // 토큰 재발급
                                "/api/v1/auth/logout",     // 로그아웃 (만료된 Access Token으로도 가능해야 함)
                                "/api/v1/auth/test/login", // 개발용 우회 로그인
                                "/actuator/**",            // 헬스체크
                                "/swagger-ui/**",          // Swagger UI
                                "/v3/api-docs/**"          // Swagger API 문서
                        ).permitAll()
                        .anyRequest().authenticated()
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
