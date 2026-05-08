package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.dto.RuleUpdateRequest;
import com.modu.backend.domain.strategy.dto.RuleUpdateResponse;
import com.modu.backend.domain.strategy.exception.StrategyErrorCode;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.entity.TradingRuleHistory;
import com.modu.backend.domain.trading.repository.TradingRuleHistoryRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("get trading risk rules")
    void getRules() {
        // given
        Long userId = 1L;
        OffsetDateTime updatedAt = OffsetDateTime.now();
        TradingRule existingRule = TradingRule.builder()
                .userId(userId)
                .stopLossPct(3L)
                .takeProfitPct(5L)
                .maxDailyOrderCount(10L)
                .dailyLossLimitAmount(500000L)
                .version(2L)
                .createdAt(updatedAt.minusDays(1))
                .updatedAt(updatedAt)
                .build();

        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.of(existingRule));

        // when
        var response = strategyRuleService.getRules(userId);

        // then
        assertThat(response.stopLossRate()).isEqualTo(3);
        assertThat(response.takeProfitRate()).isEqualTo(5);
        assertThat(response.maxDailyOrderCount()).isEqualTo(10L);
        assertThat(response.maxDailyLossAmount()).isEqualTo(500000L);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(response.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("risk rules not found")
    void getRulesNotFound() {
        // given
        Long userId = 1L;
        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> strategyRuleService.getRules(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(StrategyErrorCode.RULE_NOT_FOUND));
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
        assertThat(response.version()).isEqualTo(0L);

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
        assertThat(savedHistory.getVersionNo()).isEqualTo(0L);
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
        RuleUpdateResponse response = strategyRuleService.updateRules(
                userId,
                new RuleUpdateRequest(3, 5, 10L, 500000L, 2L)
        );

        // then
        assertThat(response.stopLossRate()).isEqualTo(3);
        assertThat(response.takeProfitRate()).isEqualTo(5);
        assertThat(response.maxDailyOrderCount()).isEqualTo(10L);
        assertThat(response.maxDailyLossAmount()).isEqualTo(500000L);
        assertThat(response.version()).isEqualTo(3L);

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
        assertThat(historyCaptor.getValue().getVersionNo()).isEqualTo(3L);
    }

    @Test
    @DisplayName("stale version request is rejected before updating trading rules")
    void updateRulesWithStaleVersion() {
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
        RuleUpdateRequest request = new RuleUpdateRequest(3, 5, 10L, 500000L, 1L);

        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.of(existingRule));

        // when & then
        assertThatThrownBy(() -> strategyRuleService.updateRules(userId, request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(tradingRuleRepository, never()).flush();
        verify(tradingRuleHistoryRepository, never()).save(any(TradingRuleHistory.class));
    }

    @Test
    @DisplayName("concurrent first create conflict is returned as optimistic locking failure")
    void createRulesWithDuplicateKey() {
        // given
        Long userId = 1L;
        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.empty());
        when(tradingRuleRepository.saveAndFlush(any(TradingRule.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // when & then
        assertThatThrownBy(() -> strategyRuleService.updateRules(userId, request()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(tradingRuleHistoryRepository, never()).save(any(TradingRuleHistory.class));
    }

    @Test
    @DisplayName("non-duplicate integrity violation keeps original exception")
    void createRulesWithNonDuplicateIntegrityViolation() {
        // given
        Long userId = 1L;
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "not null violation",
                new SQLException("not null violation", "23502")
        );

        when(tradingRuleRepository.findById(userId)).thenReturn(Optional.empty());
        when(tradingRuleRepository.saveAndFlush(any(TradingRule.class)))
                .thenThrow(exception);

        // when & then
        assertThatThrownBy(() -> strategyRuleService.updateRules(userId, request()))
                .isSameAs(exception);
        verify(tradingRuleHistoryRepository, never()).save(any(TradingRuleHistory.class));
    }

    private RuleUpdateRequest request() {
        return new RuleUpdateRequest(
                3,
                5,
                10L,
                500000L,
                0L
        );
    }
}
