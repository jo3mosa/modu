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

/**
 * 개발용 우회 로그인 컨트롤러
 *
 * local 프로파일에서만 활성화, 운영 환경에서는 빈 자체가 등록되지 않음
 */
@Tag(name = "Auth", description = "인증 API (소셜 로그인, 토큰 재발급, 로그아웃)")
@Profile("local")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TestAuthController {

    private final AuthService authService;

    @Operation(
            summary = "[개발용] 테스트 로그인",
            description = """
                    **로컬 개발 환경 전용** API입니다. 운영 환경에서는 비활성화됩니다.

                    카카오 OAuth 없이 `userId`만으로 로그인 처리합니다.
                    DB에 해당 `userId`의 사용자가 존재해야 합니다.

                    **응답 쿠키**
                    - `accessToken`: 1시간 유효
                    - `refreshToken`: 14일 유효
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "테스트 로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 userId")
    })
    @SecurityRequirements
    @PostMapping("/test/login")
    public ResponseEntity<ApiResponse<LoginResponse>> testLogin(
            @RequestBody @Valid TestLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.testLogin(request.userId(), response);
        return ResponseEntity.ok(ApiResponse.success("개발용 테스트 로그인 성공", loginResponse));
    }
}
