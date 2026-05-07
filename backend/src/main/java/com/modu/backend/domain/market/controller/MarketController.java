package com.modu.backend.domain.market.controller;

import com.modu.backend.domain.market.dto.CandleListResponse;
import com.modu.backend.domain.market.dto.StockDetailResponse;
import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.service.MarketService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Market", description = "종목/시세 API")
@Validated
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
                    - `page`: 1 이상, `size`: 1~100 허용
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 page/size 파라미터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping("/stocks")
    public ResponseEntity<ApiResponse<StockListResponse>> getStocks(
            @Parameter(description = "검색어 (종목명 또는 종목코드). 없으면 전체 조회", example = "삼성전자")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (1 이상, 기본값: 1)", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int page,

            @Parameter(description = "페이지 크기 (1~100, 기본값: 20)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        StockListResponse response = marketService.getStocks(keyword, page, size);
        return ResponseEntity.ok(ApiResponse.success("종목 목록을 성공적으로 조회했습니다.", response));
    }

    @Operation(
            summary = "종목 상세 조회",
            description = """
                    종목코드로 실시간 시세 정보를 조회합니다.

                    - 한국투자증권 API를 통해 실시간 시세 데이터를 가져옵니다.
                    - 응답은 Redis에 3초간 캐싱됩니다. (Rate Limit 방어)
                    - stock_master에 등록되지 않은 종목코드는 404를 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 종목코드",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"존재하지 않는 종목코드입니다.\",\"errorCode\":\"MKT_001\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS 시세 조회 실패")
    })
    @GetMapping("/stocks/{stockCode}")
    public ResponseEntity<ApiResponse<StockDetailResponse>> getStockDetail(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode) {

        StockDetailResponse response = marketService.getStockDetail(stockCode);
        return ResponseEntity.ok(ApiResponse.success("종목 상세 정보를 성공적으로 조회했습니다.", response));
    }

    @Operation(
            summary = "차트 캔들 데이터 조회",
            description = """
                    종목코드와 기간 타입으로 캔들 데이터를 조회합니다.

                    **period 값:**
                    - `D`: 일봉 (기본 startDate: 오늘 - 6개월)
                    - `W`: 주봉 (기본 startDate: 오늘 - 2년)
                    - `M`: 월봉 (기본 startDate: 오늘 - 5년)
                    - `1`: 1분봉 (기본: 당일 데이터)
                    - `5`: 5분봉 (기본: 당일 데이터)
                    - `60`: 60분봉 (기본: 당일 데이터)

                    - 응답은 과거순(오래된 것부터)으로 정렬됩니다.
                    - `startDate`/`endDate` 미입력 시 period별 기본값이 적용됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않은 period 값",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"유효하지 않은 기간 타입입니다.\",\"errorCode\":\"MKT_003\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS 캔들 조회 실패")
    })
    @GetMapping("/stocks/{stockCode}/candles")
    public ResponseEntity<ApiResponse<CandleListResponse>> getCandleData(
            @Parameter(description = "종목코드 (6자리)", example = "005930")
            @PathVariable String stockCode,

            @Parameter(description = "기간 타입 (D/W/M/1/5/60)", example = "D")
            @RequestParam String period,

            @Parameter(description = "시작일 (YYYYMMDD). 없으면 period별 기본값", example = "20250301")
            @RequestParam(required = false) String startDate,

            @Parameter(description = "종료일 (YYYYMMDD). 없으면 오늘", example = "20250426")
            @RequestParam(required = false) String endDate) {

        CandleListResponse response = marketService.getCandleData(stockCode, period, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("차트 캔들 데이터를 성공적으로 조회했습니다.", response));
    }
}
