package com.modu.backend.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI 3) 전역 설정
 *
 * 인증이 필요한 API는 Swagger UI 우상단 Authorize 버튼에서
 * accessToken 쿠키 값을 입력하면 테스트 가능
 */
@Configuration
public class SwaggerConfig {

    private static final String COOKIE_AUTH = "accessToken";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MODU 주식 트레이딩 플랫폼 API")
                        .description("MODU 백엔드 API 명세서")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_AUTH, new SecurityScheme()
                                .name(COOKIE_AUTH)
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .description("로그인 후 발급되는 Access Token 쿠키")));
    }
}
