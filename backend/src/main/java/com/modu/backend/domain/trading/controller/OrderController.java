package com.modu.backend.domain.trading.controller;

import com.modu.backend.domain.trading.dto.ModifyOrderRequest;
import com.modu.backend.domain.trading.dto.ModifyOrderResponse;
import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
import com.modu.backend.domain.trading.dto.PendingOrdersResponse;
import com.modu.backend.domain.trading.service.OrderService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Orders", description = "주문 API")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

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
}
