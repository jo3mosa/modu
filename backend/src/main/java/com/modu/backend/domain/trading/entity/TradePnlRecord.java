package com.modu.backend.domain.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 거래 손익 기록 (trade_pnl_records 테이블) — S14P31B106-291
 *
 * 매도 전량 체결 시 매수와 매도 한 쌍을 묶어 1건 INSERT.
 * 회고 Agent 의 트리거 (trade.settled 토픽) 의 핵심 데이터 출처.
 *
 * [필드 의미]
 *  buy_order_id    매수 주문 ID (FIFO 매칭 — 가장 오래된 활성 매수 주문)
 *  sell_order_id   매도 주문 ID
 *  quantity        매도 체결 총 수량 (=매수 전량 수량과 같음. 1매수 = 1매도 가정)
 *  avg_buy_price   매수 가중 평균 단가
 *  sell_price      매도 평균 단가 (다중 부분 체결의 가중 평균)
 *  gross_pnl       (sell_price − avg_buy_price) × quantity
 *  commission      KIS 수수료 (현 단계 0 — KIS 응답에 없어 followups)
 *  tax             세금 (현 단계 0)
 *  net_pnl         gross_pnl − commission − tax
 *  holding_days    매수 → 매도 사이 영업일 (단순 calendar day 차이로 시작, 영업일 계산 followups)
 *  closed_at       매도 전량 체결 시각
 *
 * [현 단계 한계]
 *  commission / tax 는 KIS 가 체결 통보에서 안 줌 — 0 으로 채움. 추후 잔고/거래내역 조회 API 로 보강 가능 (followups).
 */
@Entity
@Table(name = "trade_pnl_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradePnlRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "buy_order_id", nullable = false)
    private Long buyOrderId;

    @Column(name = "sell_order_id", nullable = false)
    private Long sellOrderId;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "avg_buy_price", nullable = false)
    private Long avgBuyPrice;

    @Column(name = "sell_price", nullable = false)
    private Long sellPrice;

    @Column(name = "gross_pnl", nullable = false)
    private Long grossPnl;

    @Column(name = "commission", nullable = false)
    private Long commission;

    @Column(name = "tax", nullable = false)
    private Long tax;

    @Column(name = "net_pnl", nullable = false)
    private Long netPnl;

    @Column(name = "holding_days", nullable = false)
    private Long holdingDays;

    @Column(name = "closed_at", nullable = false)
    private OffsetDateTime closedAt;

    @Builder
    public TradePnlRecord(String stockCode, Long userId,
                          Long buyOrderId, Long sellOrderId,
                          Long quantity, Long avgBuyPrice, Long sellPrice,
                          Long commission, Long tax,
                          Long holdingDays, OffsetDateTime closedAt) {
        this.stockCode = stockCode;
        this.userId = userId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.sellPrice = sellPrice;
        this.commission = commission == null ? 0L : commission;
        this.tax = tax == null ? 0L : tax;
        this.grossPnl = (sellPrice - avgBuyPrice) * quantity;
        this.netPnl = this.grossPnl - this.commission - this.tax;
        this.holdingDays = holdingDays;
        this.closedAt = closedAt;
    }
}
