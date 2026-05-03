package com.modu.backend.domain.auth.controller;

import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.SocialLoginRequest;
import com.modu.backend.domain.auth.service.AuthService;
import com.modu.backend.global.dto.ApiResponse;
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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /api/v1/auth/social/{provider} — 소셜 로그인 */
    @PostMapping("/social/{provider}")
    public ResponseEntity<ApiResponse<LoginResponse>> socialLogin(
            @PathVariable String provider,
            @RequestBody @Valid SocialLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.socialLogin(provider, request.code(), response);
        return ResponseEntity.ok(ApiResponse.success("로그인에 성공했습니다.", loginResponse));
    }

    /** POST /api/v1/auth/refresh — Access Token 재발급 */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.refresh(request, response);
        return ResponseEntity.ok(ApiResponse.success("토큰이 성공적으로 재발급되었습니다."));
    }

    /** POST /api/v1/auth/logout — 로그아웃 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.logout(request, response);
        return ResponseEntity.ok(ApiResponse.success("성공적으로 로그아웃 되었습니다."));
    }
}
