package com.modu.backend.global.kafka.constant;

public class KafkaTopic {

    public static final String NEWS_ARTICLE_PUBLISHED = "news.article.published";
    public static final String MARKET_SIGNAL_DETECTED = "market.signal.detected";
    public static final String AI_TRIGGER_REQUESTED   = "ai.trigger.requested";
    public static final String AI_DECISION_GENERATED  = "ai.decision.generated";
    public static final String AI_AGENT_MESSAGE      = "ai.agent.message";
    public static final String TRADE_ORDER_SUBMITTED  = "trade.order.submitted";
    public static final String TRADE_ORDER_EXECUTED   = "trade.order.executed";

    /**
     * 거래 정산 완료 — S14P31B106-291.
     * SELL 전량 체결 + trade_pnl_records INSERT 완료 직후 발행. 회고(Post-Mortem) Agent 트리거.
     */
    public static final String TRADE_SETTLED          = "trade.settled";
}
