package com.modu.backend.domain.auth.controller;

import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.SocialLoginRequest;
import com.modu.backend.domain.auth.dto.TokenResponse;
import com.modu.backend.domain.auth.service.AuthService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "소셜 로그인",
            description = """
                    카카오 OAuth 인가 코드로 로그인합니다.

                    - Access Token은 응답 본문의 `data.accessToken`으로 반환합니다.
                    - Refresh Token은 `HttpOnly`, `Secure`, `SameSite=Strict` 쿠키로 발급합니다.
                    - 이후 인증이 필요한 API는 `Authorization: Bearer {accessToken}` 헤더를 사용해야 합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 소셜 로그인 제공자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "외부 OAuth 제공자 API 호출 실패")
    })
    @SecurityRequirements
    @PostMapping("/social/{provider}")
    public ResponseEntity<ApiResponse<LoginResponse>> socialLogin(
            @Parameter(description = "소셜 로그인 제공자. 현재는 kakao만 지원합니다.", example = "kakao")
            @PathVariable String provider,
            @RequestBody @Valid SocialLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.socialLogin(provider, request.code(), response);
        return ResponseEntity.ok(ApiResponse.success("로그인에 성공했습니다.", loginResponse));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = """
                    Refresh Token 쿠키를 검증해 새로운 Access Token을 발급합니다.

                    - 요청 시 `refreshToken` 쿠키가 필요합니다.
                    - 기존 Refresh Token은 폐기하고 새 Refresh Token 쿠키를 발급합니다.
                    - 새 Access Token은 응답 본문의 `data.accessToken`으로 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token 없음, 만료 또는 폐기됨")
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {

        TokenResponse tokenResponse = authService.refresh(request, response);
        return ResponseEntity.ok(ApiResponse.success("Access Token이 재발급되었습니다.", tokenResponse));
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    현재 로그인 상태를 종료합니다.

                    - `Authorization: Bearer {accessToken}` 헤더가 있으면 해당 Access Token을 Redis blacklist에 등록합니다.
                    - Refresh Token은 DB에서 폐기 처리합니다.
                    - 클라이언트의 `refreshToken` 쿠키는 만료 처리합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공")
    })
    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.logout(request, response);
        return ResponseEntity.ok(ApiResponse.success("로그아웃되었습니다."));
    }
}
