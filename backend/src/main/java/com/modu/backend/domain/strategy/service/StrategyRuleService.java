package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.dto.RuleUpdateRequest;
import com.modu.backend.domain.strategy.dto.RuleUpdateResponse;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.entity.TradingRuleHistory;
import com.modu.backend.domain.trading.repository.TradingRuleHistoryRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class StrategyRuleService {

    private final TradingRuleRepository tradingRuleRepository;
    private final TradingRuleHistoryRepository tradingRuleHistoryRepository;

    @Transactional
    public RuleUpdateResponse updateRules(Long userId, RuleUpdateRequest request) {
        OffsetDateTime now = OffsetDateTime.now();

        TradingRule rule = tradingRuleRepository.findById(userId)
                .map(existing -> {
                    updateExistingRule(existing, request, now);
                    tradingRuleRepository.flush();
                    return existing;
                })
                .orElseGet(() -> tradingRuleRepository.saveAndFlush(createRule(userId, request, now)));

        tradingRuleHistoryRepository.save(toHistory(rule, now));

        return new RuleUpdateResponse(
                rule.getStopLossPct().intValue(),
                rule.getTakeProfitPct().intValue(),
                rule.getMaxDailyOrderCount(),
                rule.getDailyLossLimitAmount(),
                rule.getUpdatedAt()
        );
    }

    private void updateExistingRule(
            TradingRule rule,
            RuleUpdateRequest request,
            OffsetDateTime now
    ) {
        rule.update(
                request.stopLossRate().longValue(),
                request.takeProfitRate().longValue(),
                request.maxDailyOrderCount(),
                request.maxDailyLossAmount(),
                now
        );
    }

    private TradingRule createRule(
            Long userId,
            RuleUpdateRequest request,
            OffsetDateTime now
    ) {
        return TradingRule.builder()
                .userId(userId)
                .stopLossPct(request.stopLossRate().longValue())
                .takeProfitPct(request.takeProfitRate().longValue())
                .maxDailyOrderCount(request.maxDailyOrderCount())
                .dailyLossLimitAmount(request.maxDailyLossAmount())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private TradingRuleHistory toHistory(TradingRule rule, OffsetDateTime now) {
        return TradingRuleHistory.builder()
                .userId(rule.getUserId())
                .stopLossPct(rule.getStopLossPct())
                .takeProfitPct(rule.getTakeProfitPct())
                .maxDailyOrderCount(rule.getMaxDailyOrderCount())
                .dailyLossLimitAmount(rule.getDailyLossLimitAmount())
                .naturalLanguageRule(rule.getNaturalLanguageRule())
                .parsedRuleJson(rule.getParsedRuleJson())
                .versionNo(rule.getVersion())
                .createdAt(now)
                .build();
    }
}
