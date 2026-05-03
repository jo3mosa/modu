package com.modu.backend.domain.user.controller;

import com.modu.backend.domain.user.dto.KisKeyRegisterRequest;
import com.modu.backend.domain.user.dto.KisKeyUpdateRequest;
import com.modu.backend.domain.user.service.KisKeyService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "User", description = "사용자 API (한국투자증권 API 연동 관리)")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final KisKeyService kisKeyService;

    @Operation(
            summary = "한국투자증권 API 연동",
            description = """
                    한국투자증권 Open API 키를 등록합니다.

                    - 사용자당 하나의 KIS 계정만 연동 가능합니다.
                    - 이미 연동된 경우 409 에러를 반환합니다. 변경은 PATCH를 사용해주세요.
                    - `appKey`, `appSecret`은 AES-256-GCM으로 암호화되어 저장됩니다.
                    - `accountNo` 형식: `계좌번호-상품코드` (예: `50012345-01`)

                    **KIS API 키 발급**: https://apiportal.koreainvestment.com
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연동 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요 (accessToken 쿠키 없음 또는 만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 KIS API가 연동된 사용자",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"이미 한국투자증권 API가 연동되어 있습니다.\",\"errorCode\":\"USER_001\"}")))
    })
    @PostMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> registerKisKey(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid KisKeyRegisterRequest request) {

        kisKeyService.registerKisKey(userId, request);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 연동이 완료되었습니다."));
    }

    @Operation(
            summary = "한국투자증권 API 정보 수정",
            description = """
                    연동된 한국투자증권 Open API 키 정보를 수정합니다.

                    - 변경할 필드만 포함해서 요청하세요. 포함하지 않은 필드는 기존 값을 유지합니다.
                    - 연동 정보가 없는 경우 404 에러를 반환합니다. 먼저 POST로 연동해주세요.
                    - `accountNo` 형식: `계좌번호-상품코드` (예: `50012345-01`)

                    **요청 예시 (appSecret만 변경)**
                    ```json
                    { "appSecret": "new_secret_key" }
                    ```
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "연동된 KIS 정보 없음",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"USER_002\"}")))
    })
    @PatchMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> updateKisKey(
            @AuthenticationPrincipal Long userId,
            @RequestBody KisKeyUpdateRequest request) {

        kisKeyService.updateKisKey(userId, request);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 정보가 수정되었습니다."));
    }

    @Operation(
            summary = "한국투자증권 API 연동 해제",
            description = """
                    연동된 한국투자증권 Open API 키를 삭제합니다.

                    - 연동 해제 후 자동매매 기능을 사용할 수 없습니다.
                    - 연동 정보가 없는 경우 404 에러를 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연동 해제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "연동된 KIS 정보 없음",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"USER_002\"}")))
    })
    @DeleteMapping("/kis-keys")
    public ResponseEntity<ApiResponse<Void>> deleteKisKey(
            @AuthenticationPrincipal Long userId) {

        kisKeyService.deleteKisKey(userId);
        return ResponseEntity.ok(ApiResponse.success("한국투자증권 API 연동이 해제되었습니다."));
    }
}
