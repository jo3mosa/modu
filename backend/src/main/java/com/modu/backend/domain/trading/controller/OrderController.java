package com.modu.backend.domain.trading.controller;

import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
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
