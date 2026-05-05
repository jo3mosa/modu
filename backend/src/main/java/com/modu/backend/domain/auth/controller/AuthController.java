package com.modu.backend.domain.auth.controller;

import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.SocialLoginRequest;
import com.modu.backend.domain.auth.service.AuthService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

@Tag(name = "Auth", description = "인증 API (소셜 로그인, 토큰 재발급, 로그아웃)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "소셜 로그인",
            description = """
                    카카오 소셜 로그인을 처리합니다.

                    **흐름**
                    1. 프론트엔드가 카카오 인증 후 리다이렉트 URL에서 `code`를 추출
                    2. 해당 `code`를 이 API로 전달
                    3. 서버가 카카오 API와 통신해 사용자 정보를 조회
                    4. 신규 사용자면 자동 회원가입 후 로그인 처리

                    **응답 쿠키**
                    - `accessToken`: 1시간 유효
                    - `refreshToken`: 14일 유효
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 provider",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"지원하지 않는 소셜 로그인 제공자입니다.\",\"errorCode\":\"AUTH_001\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "카카오 API 호출 실패",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"카카오 액세스 토큰 발급에 실패했습니다.\",\"errorCode\":\"AUTH_002\"}")))
    })
    @SecurityRequirements
    @PostMapping("/social/{provider}")
    public ResponseEntity<ApiResponse<LoginResponse>> socialLogin(
            @Parameter(description = "소셜 제공자 (현재 지원: kakao)", example = "kakao")
            @PathVariable String provider,
            @RequestBody @Valid SocialLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.socialLogin(provider, request.code(), response);
        return ResponseEntity.ok(ApiResponse.success("로그인에 성공했습니다.", loginResponse));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = """
                    만료된 Access Token을 재발급합니다.

                    - 요청 쿠키의 `refreshToken`을 검증한 후 새 `accessToken`과 `refreshToken`을 발급합니다.
                    - Refresh Token 로테이션 적용: 재발급 시 기존 Refresh Token은 폐기되고 새 토큰이 발급됩니다.
                    - 만료되거나 폐기된 Refresh Token은 사용 불가합니다.

                    **요청 쿠키** (자동 전송)
                    - `refreshToken`: 로그인 시 발급된 Refresh Token
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token 없음 또는 유효하지 않음",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"Refresh Token이 존재하지 않습니다.\",\"errorCode\":\"AUTH_006\"}")))
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.refresh(request, response);
        return ResponseEntity.ok(ApiResponse.success("토큰이 성공적으로 재발급되었습니다."));
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    현재 로그인된 세션을 종료합니다.

                    - Refresh Token을 폐기하고 Access Token / Refresh Token 쿠키를 만료시킵니다.
                    - Access Token이 만료된 상태에서도 로그아웃 요청이 가능합니다.
                    - Refresh Token 쿠키가 없어도 로그아웃은 성공 처리됩니다.
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
        return ResponseEntity.ok(ApiResponse.success("성공적으로 로그아웃 되었습니다."));
    }
}
