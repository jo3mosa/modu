package com.modu.backend.domain.market.controller;

import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.service.MarketService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Market", description = "종목/시세 API")
@RestController
@RequestMapping("/api/v1/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @Operation(
            summary = "종목 전체 조회 및 검색",
            description = """
                    활성 종목 목록을 페이징하여 조회하거나 키워드로 검색합니다.

                    - `keyword` 없음: 전체 종목 목록 조회
                    - `keyword` 있음: 종목명 또는 종목코드 부분 일치 검색
                    - 상장폐지된 종목(`is_active = false`)은 결과에서 제외됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping("/stocks")
    public ResponseEntity<ApiResponse<StockListResponse>> getStocks(
            @Parameter(description = "검색어 (종목명 또는 종목코드). 없으면 전체 조회", example = "삼성전자")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (기본값: 1)", example = "1")
            @RequestParam(defaultValue = "1") int page,

            @Parameter(description = "페이지 크기 (기본값: 20)", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        StockListResponse response = marketService.getStocks(keyword, page, size);
        return ResponseEntity.ok(ApiResponse.success("종목 목록을 성공적으로 조회했습니다.", response));
    }
}
