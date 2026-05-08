package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.dto.RuleUpdateRequest;
import com.modu.backend.domain.strategy.dto.RuleUpdateResponse;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.entity.TradingRuleHistory;
import com.modu.backend.domain.trading.repository.TradingRuleHistoryRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyRuleServiceTest {

    @Mock TradingRuleRepository tradingRuleRepository;
    @Mock TradingRuleHistoryRepository tradingRuleHistoryRepository;

    StrategyRuleService strategyRuleService;

    @BeforeEach
    void setUp() {
        strategyRuleService = new StrategyRuleService(
                tradingRuleRepository,
                tradingRuleHistoryRepository
        );
    }

    @Test
    @DisplayName("create trading risk rules and save history")
    void createRules() {
        // given
        Long userId = 1L;
        RuleUpdateRequest request = request();

        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.empty());
        when(tradingRuleRepository.saveAndFlush(any(TradingRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        RuleUpdateResponse response = strategyRuleService.updateRules(userId, request);

        // then
        assertThat(response.stopLossRate()).isEqualTo(3);
        assertThat(response.takeProfitRate()).isEqualTo(5);
        assertThat(response.maxDailyOrderCount()).isEqualTo(10L);
        assertThat(response.maxDailyLossAmount()).isEqualTo(500000L);
        assertThat(response.updatedAt()).isNotNull();

        ArgumentCaptor<TradingRule> ruleCaptor = ArgumentCaptor.forClass(TradingRule.class);
        verify(tradingRuleRepository).saveAndFlush(ruleCaptor.capture());

        TradingRule savedRule = ruleCaptor.getValue();
        assertThat(savedRule.getUserId()).isEqualTo(userId);
        assertThat(savedRule.getStopLossPct()).isEqualTo(3L);
        assertThat(savedRule.getTakeProfitPct()).isEqualTo(5L);
        assertThat(savedRule.getMaxDailyOrderCount()).isEqualTo(10L);
        assertThat(savedRule.getDailyLossLimitAmount()).isEqualTo(500000L);
        assertThat(savedRule.getCreatedAt()).isNotNull();
        assertThat(savedRule.getUpdatedAt()).isNotNull();

        ArgumentCaptor<TradingRuleHistory> historyCaptor =
                ArgumentCaptor.forClass(TradingRuleHistory.class);
        verify(tradingRuleHistoryRepository).save(historyCaptor.capture());

        TradingRuleHistory savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getUserId()).isEqualTo(userId);
        assertThat(savedHistory.getStopLossPct()).isEqualTo(3L);
        assertThat(savedHistory.getTakeProfitPct()).isEqualTo(5L);
        assertThat(savedHistory.getMaxDailyOrderCount()).isEqualTo(10L);
        assertThat(savedHistory.getDailyLossLimitAmount()).isEqualTo(500000L);
        assertThat(savedHistory.getVersionNo()).isNull();
    }

    @Test
    @DisplayName("update trading risk rules and preserve createdAt")
    void updateRules() {
        // given
        Long userId = 1L;
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        TradingRule existingRule = TradingRule.builder()
                .userId(userId)
                .stopLossPct(2L)
                .takeProfitPct(4L)
                .maxDailyOrderCount(5L)
                .dailyLossLimitAmount(300000L)
                .version(2L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();

        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.of(existingRule));

        // when
        RuleUpdateResponse response = strategyRuleService.updateRules(userId, request());

        // then
        assertThat(response.stopLossRate()).isEqualTo(3);
        assertThat(response.takeProfitRate()).isEqualTo(5);
        assertThat(response.maxDailyOrderCount()).isEqualTo(10L);
        assertThat(response.maxDailyLossAmount()).isEqualTo(500000L);

        assertThat(existingRule.getCreatedAt()).isEqualTo(createdAt);
        assertThat(existingRule.getUpdatedAt()).isAfter(createdAt);
        assertThat(existingRule.getStopLossPct()).isEqualTo(3L);
        assertThat(existingRule.getTakeProfitPct()).isEqualTo(5L);
        assertThat(existingRule.getMaxDailyOrderCount()).isEqualTo(10L);
        assertThat(existingRule.getDailyLossLimitAmount()).isEqualTo(500000L);
        verify(tradingRuleRepository, never()).saveAndFlush(any(TradingRule.class));
        verify(tradingRuleRepository).flush();

        ArgumentCaptor<TradingRuleHistory> historyCaptor =
                ArgumentCaptor.forClass(TradingRuleHistory.class);
        verify(tradingRuleHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getVersionNo()).isEqualTo(2L);
    }

    private RuleUpdateRequest request() {
        return new RuleUpdateRequest(
                3,
                5,
                10L,
                500000L
        );
    }
}
