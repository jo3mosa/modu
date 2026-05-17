package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.strategy.dto.AutoTradeStatusRequest;
import com.modu.backend.domain.strategy.dto.AutoTradeStatusResponse;
import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
import com.modu.backend.domain.strategy.exception.StrategyErrorCode;
import com.modu.backend.domain.trading.entity.TradingRule;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoTradeStatusServiceTest {

    @Mock AutoTradeSettingsRepository autoTradeSettingsRepository;
    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock InvestmentProfileRepository investmentProfileRepository;
    @Mock TradingRuleRepository tradingRuleRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks
    AutoTradeStatusService service;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 기본 — 선행 검증 통과
        when(kisCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(KisCredential.builder().userId(USER_ID).build()));
        when(investmentProfileRepository.findById(USER_ID)).thenReturn(Optional.of(InvestmentProfile.builder().userId(USER_ID).build()));
        when(tradingRuleRepository.findById(USER_ID)).thenReturn(Optional.of(TradingRule.builder().userId(USER_ID).build()));
    }

    @Test
    @DisplayName("isActive=true: INACTIVE → ACTIVE 전환, Redis SET")
    void activateFromInactive() {
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.INACTIVE).build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        AutoTradeStatusResponse response = service.updateStatus(USER_ID, new AutoTradeStatusRequest(true));

        assertThat(settings.getAutoTradeStatus()).isEqualTo(AutoTradeStatus.ACTIVE);
        assertThat(response.isActive()).isTrue();
        verify(valueOps).set(eq("auto-trade:status:1"), eq("ACTIVE"));
    }

    @Test
    @DisplayName("isActive=true: 신규 사용자 (row 없음) → INSERT + ACTIVE")
    void activateForNewUser() {
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(autoTradeSettingsRepository.save(any(AutoTradeSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AutoTradeStatusResponse response = service.updateStatus(USER_ID, new AutoTradeStatusRequest(true));

        assertThat(response.isActive()).isTrue();
        verify(autoTradeSettingsRepository).save(any(AutoTradeSettings.class));
        verify(valueOps).set(eq("auto-trade:status:1"), eq("ACTIVE"));
    }

    @Test
    @DisplayName("isActive=true + KIS 미연동: KIS_NOT_CONNECTED 예외")
    void activateWithoutKisFails() {
        when(kisCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(USER_ID, new AutoTradeStatusRequest(true)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED);
    }

    @Test
    @DisplayName("isActive=true + 투자 성향 미입력: PROFILE_NOT_FOUND 예외")
    void activateWithoutProfileFails() {
        when(investmentProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(USER_ID, new AutoTradeStatusRequest(true)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(InvestmentErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    @DisplayName("isActive=true + 룰셋 미설정: RULE_NOT_FOUND 예외")
    void activateWithoutRuleFails() {
        when(tradingRuleRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(USER_ID, new AutoTradeStatusRequest(true)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(StrategyErrorCode.RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("isActive=false: 선행 검증 skip + INACTIVE 전환")
    void inactivateSkipsPrerequisites() {
        when(kisCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty()); // 검증 실패해야 할 stub
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.ACTIVE).build();
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        AutoTradeStatusResponse response = service.updateStatus(USER_ID, new AutoTradeStatusRequest(false));

        assertThat(settings.getAutoTradeStatus()).isEqualTo(AutoTradeStatus.INACTIVE);
        assertThat(response.isActive()).isFalse();
        verify(valueOps).set(eq("auto-trade:status:1"), eq("INACTIVE"));
    }

    @Test
    @DisplayName("isActive=true: KILL_SWITCHED → ACTIVE 로 해제")
    void activateFromKillSwitched() {
        AutoTradeSettings settings = AutoTradeSettings.builder()
                .userId(USER_ID).autoTradeStatus(AutoTradeStatus.KILL_SWITCHED).build();
        settings.triggerKillSwitch("test");
        when(autoTradeSettingsRepository.findById(USER_ID)).thenReturn(Optional.of(settings));

        AutoTradeStatusResponse response = service.updateStatus(USER_ID, new AutoTradeStatusRequest(true));

        assertThat(settings.getAutoTradeStatus()).isEqualTo(AutoTradeStatus.ACTIVE);
        assertThat(settings.getKillSwitchReason()).isNull();
        assertThat(settings.getKillSwitchTriggeredAt()).isNull();
        assertThat(response.isActive()).isTrue();
    }
}
