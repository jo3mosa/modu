package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.dto.RuleResponse;
import com.modu.backend.domain.strategy.dto.RuleUpdateRequest;
import com.modu.backend.domain.strategy.dto.RuleUpdateResponse;
import com.modu.backend.domain.strategy.exception.StrategyErrorCode;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.entity.TradingRuleHistory;
import com.modu.backend.domain.trading.repository.TradingRuleHistoryRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class StrategyRuleService {

    private static final String POSTGRES_DUPLICATE_KEY_SQL_STATE = "23505";

    private final TradingRuleRepository tradingRuleRepository;
    private final TradingRuleHistoryRepository tradingRuleHistoryRepository;

    @Transactional(readOnly = true)
    public RuleResponse getRules(Long userId) {
        TradingRule rule = tradingRuleRepository.findById(userId)
                .orElseThrow(() -> new ApiException(StrategyErrorCode.RULE_NOT_FOUND));

        return new RuleResponse(
                rule.getStopLossPct().intValue(),
                rule.getTakeProfitPct().intValue(),
                rule.getMaxDailyOrderCount(),
                rule.getDailyLossLimitAmount(),
                rule.getAiBudgetAmount(),
                rule.getUpdatedAt(),
                rule.getVersion()
        );
    }

    @Transactional
    public RuleUpdateResponse updateRules(Long userId, RuleUpdateRequest request) {
        OffsetDateTime now = OffsetDateTime.now();

        TradingRule rule;
        Long historyVersionNo;

        var existingRule = tradingRuleRepository.findById(userId);
        if (existingRule.isPresent()) {
            rule = existingRule.get();
            historyVersionNo = nextVersion(rule);
            updateExistingRule(rule, request, now);
            tradingRuleRepository.flush();
        } else {
            rule = createNewRule(userId, request, now);
            historyVersionNo = 0L;
        }

        tradingRuleHistoryRepository.save(toHistory(rule, historyVersionNo, now));

        return new RuleUpdateResponse(
                rule.getStopLossPct().intValue(),
                rule.getTakeProfitPct().intValue(),
                rule.getMaxDailyOrderCount(),
                rule.getDailyLossLimitAmount(),
                rule.getAiBudgetAmount(),
                rule.getUpdatedAt(),
                historyVersionNo
        );
    }

    private void updateExistingRule(
            TradingRule rule,
            RuleUpdateRequest request,
            OffsetDateTime now
    ) {
        validateVersion(rule, request.version());
        rule.update(
                request.stopLossRate().longValue(),
                request.takeProfitRate().longValue(),
                request.maxDailyOrderCount(),
                request.maxDailyLossAmount(),
                request.aiBudgetAmount(),
                now
        );
    }

    private TradingRule createNewRule(
            Long userId,
            RuleUpdateRequest request,
            OffsetDateTime now
    ) {
        validateNewRuleVersion(request.version());
        try {
            return tradingRuleRepository.saveAndFlush(createRule(userId, request, now));
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKeyViolation(e)) {
                throw e;
            }
            throw new ObjectOptimisticLockingFailureException(TradingRule.class, userId);
        }
    }

    private boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }

        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && POSTGRES_DUPLICATE_KEY_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }

        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase().contains("duplicate key");
    }

    private void validateVersion(TradingRule rule, Long requestVersion) {
        if (requestVersion == null) {
            throw new ValidationException("리스크 룰셋 version은 필수입니다.");
        }
        if (!requestVersion.equals(rule.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(TradingRule.class, rule.getUserId());
        }
    }

    private void validateNewRuleVersion(Long requestVersion) {
        if (requestVersion == null) {
            throw new ValidationException("리스크 룰셋 version은 필수입니다.");
        }
        if (requestVersion != 0L) {
            throw new ObjectOptimisticLockingFailureException(TradingRule.class, null);
        }
    }

    private Long nextVersion(TradingRule rule) {
        return rule.getVersion() == null ? 0L : rule.getVersion() + 1;
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
                .aiBudgetAmount(request.aiBudgetAmount())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private TradingRuleHistory toHistory(TradingRule rule, Long versionNo, OffsetDateTime now) {
        return TradingRuleHistory.builder()
                .userId(rule.getUserId())
                .stopLossPct(rule.getStopLossPct())
                .takeProfitPct(rule.getTakeProfitPct())
                .maxDailyOrderCount(rule.getMaxDailyOrderCount())
                .dailyLossLimitAmount(rule.getDailyLossLimitAmount())
                .aiBudgetAmount(rule.getAiBudgetAmount())
                .naturalLanguageRule(rule.getNaturalLanguageRule())
                .parsedRuleJson(rule.getParsedRuleJson())
                .versionNo(versionNo)
                .createdAt(now)
                .build();
    }
}
