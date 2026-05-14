package com.modu.backend.domain.trading.controller;

import com.modu.backend.domain.trading.dto.BuyingPowerResponse;
import com.modu.backend.domain.trading.dto.ModifyOrderRequest;
import com.modu.backend.domain.trading.dto.ModifyOrderResponse;
import com.modu.backend.domain.auth.service.SseTokenService;
import com.modu.backend.domain.trading.dto.OrderHistoryResponse;
import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
import com.modu.backend.domain.trading.dto.PendingOrdersResponse;
import com.modu.backend.domain.trading.dto.SseTokenResponse;
import com.modu.backend.domain.trading.entity.HistorySourceFilter;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.service.OrderHistoryService;
import com.modu.backend.domain.trading.service.OrderService;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Orders", description = "주문 API")
@Validated
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderHistoryService orderHistoryService;
    private final SseTokenService sseTokenService;
    private final OrderSseEmitterManager orderSseEmitterManager;

    @Operation(
            summary = "미체결 주문 조회",
            description = """
                    한국투자증권 API를 통해 현재 미체결 상태인 주문 목록을 조회합니다.

                    - KIS API 미연동 시 404(KIS_NOT_CONNECTED)를 반환합니다.
                    - 모의투자 계좌는 지원하지 않습니다.
                    - 한 번에 최대 50건까지 조회됩니다. (KIS API 단일 페이지 제한)
                    - filledQuantity(체결수량)와 remainQuantity(미체결잔량)는 KIS 실시간 데이터 기준입니다.
                    - orderId/source/createdAt 은 우리 시스템에 기록된 주문에만 제공됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "미체결 주문 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"KIS_NOT_CONNECTED\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패")
    })
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PendingOrdersResponse>> getPendingOrders(
            @AuthenticationPrincipal Long userId
    ) {
        PendingOrdersResponse response = orderService.getPendingOrders(userId);
        return ResponseEntity.ok(ApiResponse.success("미체결 주문을 조회했습니다.", response));
    }

    @Operation(
            summary = "거래 이력 조회",
            description = """
                    한국투자증권 API(inquire-daily-ccld)를 통해 기간 단위 전체 거래 이력을 조회합니다.

                    - KIS API 미연동 시 404(KIS_NOT_CONNECTED) 반환
                    - 모의투자 계좌는 지원하지 않음
                    - from/to 생략 시 최근 3개월 자동 적용
                    - 조회 기간 최대 1년 (초과 시 ORDER_007)
                    - source=AUTO/MANUAL 필터 시 우리 DB에 없는 주문(KIS 직접 주문 등)은 제외됨
                    - createdAt: DB 매칭 주문은 orders.created_at, 미매칭은 KIS 주문일시(KST)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래 이력 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "기간 검증 실패",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"조회 기간은 최대 1년까지 가능합니다.\",\"errorCode\":\"ORDER_007\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패")
    })
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<OrderHistoryResponse>> getOrderHistory(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false, defaultValue = "ALL") HistorySourceFilter source,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate to,
            @RequestParam(required = false, defaultValue = "1") @Positive Integer page,
            @RequestParam(required = false, defaultValue = "20") @Positive Integer size
    ) {
        OrderHistoryResponse response = orderHistoryService.getOrderHistory(userId, source, from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success("거래 이력을 조회했습니다.", response));
    }

    @Operation(
            summary = "주문 가능 금액/수량 조회",
            description = """
                    한국투자증권 API를 통해 주문 가능 금액과 수량을 조회합니다.

                    [stockCode 선택 파라미터]
                    - stockCode 미전달 + side=BUY: 매수가능금액(maxBuyAmount), 주문가능현금(availableCash) 조회.
                      maxBuyQuantity=0 반환 (종목 미지정 시 수량 계산 불가)
                    - stockCode 미전달 + side=SELL: 400(ORDER_002) 반환 (매도는 종목코드 필수)

                    [응답 필드]
                    - maxBuyAmount: 미수 없는 최대 매수 가능 금액
                    - maxBuyQuantity: 최대 매수 가능 수량 (side=SELL 시 0)
                    - maxSellQuantity: 최대 매도 가능 수량 (side=BUY 시 0)
                    - availableCash: 주문 가능 현금 (미체결 주문 차감 후 실사용 가능 금액)

                    [기타]
                    - orderPrice 미전달 시 시장가 기준으로 조회합니다.
                    - 모의투자 계좌는 지원하지 않습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주문 가능 정보 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "SELL 조회 시 종목코드 누락",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"매도 조회 시 종목코드가 필요합니다.\",\"errorCode\":\"ORDER_002\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"KIS_NOT_CONNECTED\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패")
    })
    @GetMapping("/buying-power")
    public ResponseEntity<ApiResponse<BuyingPowerResponse>> getBuyingPower(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String stockCode,
            @RequestParam OrderSide side,
            @Positive @RequestParam(required = false) Long orderPrice
    ) {
        BuyingPowerResponse response = orderService.getBuyingPower(userId, stockCode, side, orderPrice);
        return ResponseEntity.ok(ApiResponse.success("주문 가능 정보를 조회했습니다.", response));
    }

    @Operation(
            summary = "미체결 주문 정정/취소",
            description = """
                    미체결(PENDING) 또는 정정됨(MODIFIED) 상태인 주문을 정정하거나 취소합니다.

                    - action=MODIFY: newQuantity 또는 newPrice 중 하나 이상 필수입니다.
                    - action=CANCEL: 미체결 잔량 전량이 취소됩니다.
                    - 이미 체결(FILLED)된 주문은 정정/취소할 수 없습니다.
                    - 정정 후 KIS에서 새 주문번호가 발급되며, 재정정/취소가 가능합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "정정/취소 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 체결된 주문 / 정정 시 변경 값 누락",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"이미 체결된 주문은 정정/취소할 수 없습니다.\",\"errorCode\":\"ORDER_004\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 주문 아님",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"본인의 주문만 정정/취소할 수 있습니다.\",\"errorCode\":\"ORDER_006\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "주문 없음 / KIS API 미연동"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패")
    })
    @PatchMapping("/{orderId}")
    public ResponseEntity<ApiResponse<ModifyOrderResponse>> modifyOrCancelOrder(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId,
            @Valid @RequestBody ModifyOrderRequest request
    ) {
        ModifyOrderResponse response = orderService.modifyOrCancelOrder(userId, orderId, request);
        return ResponseEntity.ok(ApiResponse.success(
                request.action() == com.modu.backend.domain.trading.entity.OrderModifyAction.MODIFY
                        ? "주문이 정정되었습니다."
                        : "주문이 취소되었습니다.",
                response));
    }

    @Operation(
            summary = "수동 주문 실행 (매수/매도)",
            description = """
                    한국투자증권 API를 통해 주식 매수/매도 주문을 실행합니다.

                    - KIS API 미연동 시 404(KIS_NOT_CONNECTED)를 반환합니다.
                    - 모의투자 계좌는 지원하지 않습니다.
                    - 장 운영 시간(평일 09:00~15:30 KST) 외 주문은 불가합니다.
                    - 오늘 누적 매수 금액이 일일 한도를 초과하면 주문이 거부됩니다.
                    - Idempotency-Key 헤더로 중복 주문을 방지할 수 있습니다. 미전송 시 서버에서 자동 생성합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "주문 접수 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잔고 부족 / 일일 한도 초과 / 장 마감",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"잔고가 부족합니다.\",\"errorCode\":\"ORDER_001\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "KIS API 미연동",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"연동된 한국투자증권 API 정보가 없습니다.\",\"errorCode\":\"KIS_NOT_CONNECTED\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "KIS API 호출 실패")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request
    ) {
        OrderResponse response = orderService.placeOrder(userId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("주문이 접수되었습니다.", response));
    }

    // ── 주문 처리 SSE ─────────────────────────────────────────────────────────

    @Operation(
            summary = "SSE 단기 토큰 발급",
            description = """
                    GET /api/v1/orders/connect 호출 시 query param 으로 사용할 단기 SSE 토큰을 발급합니다.

                    - EventSource API 가 Authorization 헤더를 전송하지 못하는 제약 회피용입니다.
                    - 토큰 수명은 60초이며, 일단 SSE 연결이 수립되면 토큰은 재사용하지 않아도 됩니다.
                    - audience 클레임("sse")으로 Access Token 과 분리됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE 토큰 발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @PostMapping("/sse-token")
    public ResponseEntity<ApiResponse<SseTokenResponse>> issueSseToken(
            @AuthenticationPrincipal Long userId
    ) {
        String token = sseTokenService.issue(userId);
        SseTokenResponse response = new SseTokenResponse(token, SseTokenService.EXPIRATION_SECONDS);
        return ResponseEntity.ok(ApiResponse.success("SSE 토큰이 발급되었습니다.", response));
    }

    @Operation(
            summary = "주문 처리 SSE 연결",
            description = """
                    주문 접수/실패/체결 결과를 실시간으로 수신하는 SSE 스트림을 시작합니다.

                    - 사전에 POST /api/v1/orders/sse-token 으로 단기 토큰을 발급받아야 합니다.
                    - 토큰은 query param 으로 전달합니다 (EventSource 가 헤더를 못 보냄).
                    - 모든 이벤트는 단일 event name "order" 로 전송되며, 본문 JSON 의 type 필드로 종류를 구분합니다.
                    - 동일 사용자가 새로 연결하면 기존 연결은 종료되고 새 연결로 대체됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE 스트림 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "SSE 토큰 누락/만료/오류",
                    content = @Content(schema = @Schema(example = "{\"success\":false,\"message\":\"SSE 토큰이 유효하지 않거나 만료되었습니다.\",\"errorCode\":\"AUTH_008\"}")))
    })
    @GetMapping(value = "/connect", produces = "text/event-stream")
    public SseEmitter connect(@RequestParam("token") String token) {
        Long userId = sseTokenService.verifyAndGetUserId(token);
        return orderSseEmitterManager.connect(userId);
    }
}
