package com.modu.backend.domain.auth.controller;

import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.TestLoginRequest;
import com.modu.backend.domain.auth.service.AuthService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@Profile("local")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TestAuthController {

    private final AuthService authService;

    @Operation(
            summary = "[로컬] 테스트 로그인",
            description = """
                    로컬 프로필에서만 사용하는 개발용 로그인 API입니다.

                    - OAuth 절차 없이 `userId`로 로그인합니다.
                    - Access Token은 응답 본문의 `data.accessToken`으로 반환합니다.
                    - Refresh Token은 `HttpOnly`, `Secure`, `SameSite=Strict` 쿠키로 발급합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "테스트 로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자")
    })
    @SecurityRequirements
    @PostMapping("/test/login")
    public ResponseEntity<ApiResponse<LoginResponse>> testLogin(
            @RequestBody @Valid TestLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.testLogin(request.userId(), response);
        return ResponseEntity.ok(ApiResponse.success("테스트 로그인에 성공했습니다.", loginResponse));
    }
}
