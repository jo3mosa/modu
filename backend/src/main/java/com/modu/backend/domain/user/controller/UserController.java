package com.modu.backend.domain.user.controller;

import com.modu.backend.domain.user.dto.KisKeyRegisterRequest;
import com.modu.backend.domain.user.dto.KisKeyUpdateRequest;
import com.modu.backend.domain.user.service.KisKeyService;
import com.modu.backend.global.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final KisKeyService kisKeyService;

    /** POST /api/v1/users/me/kis-keys — 한국투자증권 API 연동 */
    @PostMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> registerKisKey(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid KisKeyRegisterRequest request) {

        kisKeyService.registerKisKey(userId, request);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 연동이 완료되었습니다."));
    }

    /** PATCH /api/v1/users/me/kis-keys — 한국투자증권 API 정보 수정 */
    @PatchMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> updateKisKey(
            @AuthenticationPrincipal Long userId,
            @RequestBody KisKeyUpdateRequest request) {

        kisKeyService.updateKisKey(userId, request);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 정보가 수정되었습니다."));
    }

    /** DELETE /api/v1/users/me/kis-keys — 한국투자증권 API 연동 해제 */
    @DeleteMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> deleteKisKey(
            @AuthenticationPrincipal Long userId) {

        kisKeyService.deleteKisKey(userId);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 연동이 해제되었습니다."));
    }
}
