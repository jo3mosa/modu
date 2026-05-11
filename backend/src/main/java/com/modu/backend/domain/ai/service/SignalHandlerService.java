package com.modu.backend.domain.ai.service;

import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import org.springframework.stereotype.Service;

/**
 * AI 판단 처리 서비스 (Signal Handler)
 * TODO: 백엔드팀 구현 필요
 *  1. ai_judgments INSERT
 *  2. BUY/SELL → orders INSERT + trade.order.submitted 발행
 *  3. HOLD → 판단 저장만
 */
@Service
public class SignalHandlerService {

    public void handle(AiDecisionMessage message) {
        // TODO: 구현 필요
    }
}
