package com.modu.backend.domain.auth.controller;

import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.TestLoginRequest;
import com.modu.backend.domain.auth.service.AuthService;
import com.modu.backend.global.dto.ApiResponse;
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
@Profile("local")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TestAuthController {

    private final AuthService authService;

    /** POST /api/v1/auth/test/login — 개발용 테스트 로그인 */
    @PostMapping("/test/login")
    public ResponseEntity<ApiResponse<LoginResponse>> testLogin(
            @RequestBody @Valid TestLoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.testLogin(request.userId(), response);
        return ResponseEntity.ok(ApiResponse.success("개발용 테스트 로그인 성공", loginResponse));
    }
}
