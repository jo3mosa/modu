package com.modu.backend.domain.account.controller;

import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.account.service.AccountService;
import com.modu.backend.domain.account.service.PortfolioService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account", description = "자산/계좌 API")
@RestController
@RequestMapping("/api/v1/accounts/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final PortfolioService portfolioService;

    @Operation(
            summary = "사용자 자산 조회",
            description = """
                    한국투자증권 API를 통해 사용자의 실시간 자산 현황을 조회합니다.

                    - KIS API 키가 연동되지 않은 경우 404(KIS_NOT_CONNECTED)를 반환합니다.
                    - 수익률은 `평가손익 / 매입금액 * 100`으로 서버에서 계산합니다.
                    - 실전투자 계좌만 지원합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "자산 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"KIS_NOT_CONNECTED\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패 (토큰 발급 실패 또는 자산 조회 실패)")
    })
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AccountSummaryResponse>> getAssetSummary(
            @AuthenticationPrincipal Long userId) {

        AccountSummaryResponse response = accountService.getAssetSummary(userId);
        return ResponseEntity.ok(ApiResponse.success("사용자 자산 조회를 성공했습니다.", response));
    }

    @Operation(
            summary = "사용자 포트폴리오 조회",
            description = """
                    한국투자증권 API를 통해 사용자의 보유 주식 목록을 조회합니다.

                    - KIS API 키가 연동되지 않은 경우 404(KIS_NOT_CONNECTED)를 반환합니다.
                    - 보유수량이 0인 종목(당일 전량 매도)은 목록에서 제외됩니다.
                    - 실전투자 계좌만 지원합니다.
                    - 한 번에 최대 50건까지 조회됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "포트폴리오 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"KIS_NOT_CONNECTED\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패 (토큰 발급 실패 또는 잔고 조회 실패)")
    })
    @GetMapping("/holdings")
    public ResponseEntity<ApiResponse<PortfolioResponse>> getPortfolio(
            @AuthenticationPrincipal Long userId) {

        PortfolioResponse response = portfolioService.getPortfolio(userId);
        return ResponseEntity.ok(ApiResponse.success("사용자 포트폴리오 조회를 성공했습니다.", response));
    }
}
