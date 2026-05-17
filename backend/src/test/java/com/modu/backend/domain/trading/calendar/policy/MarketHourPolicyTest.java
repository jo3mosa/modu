package com.modu.backend.domain.trading.calendar.policy;

import com.modu.backend.domain.trading.calendar.service.TradingCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MarketHourPolicy 경계값 검증 — S14P31B106-336
 *
 * KIS 시간 구간 정의에 따른 분기 매트릭스를 시각/개장일 조합으로 모두 커버.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketHourPolicyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock TradingCalendarService tradingCalendarService;

    MarketHourPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MarketHourPolicy(tradingCalendarService);
    }

    // ──────────────────────────────────────────────────────────
    // 개장일 (평일 영업일)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("개장일 09:00 — REGULAR")
    void open_regularOpen() {
        givenOpen();
        assertThat(policy.classify(at(9, 0))).isEqualTo(MarketHourPhase.REGULAR);
    }

    @Test
    @DisplayName("개장일 15:29 — REGULAR (close 직전)")
    void open_justBeforeClose() {
        givenOpen();
        assertThat(policy.classify(at(15, 29))).isEqualTo(MarketHourPhase.REGULAR);
    }

    @Test
    @DisplayName("개장일 15:30 — WAITING (E gap 진입)")
    void open_eGapStart() {
        givenOpen();
        assertThat(policy.classify(at(15, 30))).isEqualTo(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);
    }

    @Test
    @DisplayName("개장일 15:39 — WAITING (E gap 끝)")
    void open_eGapEnd() {
        givenOpen();
        assertThat(policy.classify(at(15, 39))).isEqualTo(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);
    }

    @Test
    @DisplayName("개장일 15:40 — RESERVED_AVAILABLE")
    void open_reservedAfternoonStart() {
        givenOpen();
        assertThat(policy.classify(at(15, 40))).isEqualTo(MarketHourPhase.RESERVED_AVAILABLE);
    }

    @Test
    @DisplayName("개장일 23:39 — RESERVED_AVAILABLE")
    void open_justBeforeSystemReset() {
        givenOpen();
        assertThat(policy.classify(at(23, 39))).isEqualTo(MarketHourPhase.RESERVED_AVAILABLE);
    }

    @Test
    @DisplayName("개장일 23:40 — REJECT (시스템 초기화)")
    void open_systemResetStart() {
        givenOpen();
        assertThat(policy.classify(at(23, 40))).isEqualTo(MarketHourPhase.REJECT);
    }

    @Test
    @DisplayName("개장일 00:09 — REJECT (시스템 초기화 끝)")
    void open_systemResetEnd() {
        givenOpen();
        assertThat(policy.classify(at(0, 9))).isEqualTo(MarketHourPhase.REJECT);
    }

    @Test
    @DisplayName("개장일 00:10 — RESERVED_AVAILABLE")
    void open_reservedMorningStart() {
        givenOpen();
        assertThat(policy.classify(at(0, 10))).isEqualTo(MarketHourPhase.RESERVED_AVAILABLE);
    }

    @Test
    @DisplayName("개장일 07:29 — RESERVED_AVAILABLE")
    void open_justBeforeReservedEnd() {
        givenOpen();
        assertThat(policy.classify(at(7, 29))).isEqualTo(MarketHourPhase.RESERVED_AVAILABLE);
    }

    @Test
    @DisplayName("개장일 07:30 — REJECT (F gap 시작, 평일이라 reject)")
    void open_fGapStart() {
        givenOpen();
        assertThat(policy.classify(at(7, 30))).isEqualTo(MarketHourPhase.REJECT);
    }

    @Test
    @DisplayName("개장일 08:59 — REJECT (F gap 끝)")
    void open_fGapEnd() {
        givenOpen();
        assertThat(policy.classify(at(8, 59))).isEqualTo(MarketHourPhase.REJECT);
    }

    // ──────────────────────────────────────────────────────────
    // 휴장일 (공휴일/주말)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("휴장일 09:00 — WAITING (공휴일 정규장 시간대)")
    void closed_regularHour() {
        givenClosed();
        assertThat(policy.classify(at(9, 0))).isEqualTo(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);
    }

    @Test
    @DisplayName("휴장일 15:29 — WAITING")
    void closed_justBeforeClose() {
        givenClosed();
        assertThat(policy.classify(at(15, 29))).isEqualTo(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);
    }

    @Test
    @DisplayName("휴장일 07:30 — WAITING (F gap 도 공휴일이면 대기)")
    void closed_fGapWaits() {
        givenClosed();
        assertThat(policy.classify(at(7, 30))).isEqualTo(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);
    }

    @Test
    @DisplayName("휴장일 15:40 — RESERVED_AVAILABLE")
    void closed_reservedAfternoon() {
        givenClosed();
        assertThat(policy.classify(at(15, 40))).isEqualTo(MarketHourPhase.RESERVED_AVAILABLE);
    }

    @Test
    @DisplayName("휴장일 23:40 — REJECT (시스템 초기화는 개장 무관)")
    void closed_systemReset() {
        givenClosed();
        assertThat(policy.classify(at(23, 40))).isEqualTo(MarketHourPhase.REJECT);
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private void givenOpen() {
        when(tradingCalendarService.isMarketOpen(any())).thenReturn(true);
    }

    private void givenClosed() {
        when(tradingCalendarService.isMarketOpen(any())).thenReturn(false);
    }

    /** KST 임의의 평일 날짜 (2026-05-20 수요일) 기준 시:분으로 OffsetDateTime 생성 */
    private OffsetDateTime at(int hour, int minute) {
        LocalDate fixedDay = LocalDate.of(2026, 5, 20);
        return LocalDateTime.of(fixedDay, java.time.LocalTime.of(hour, minute))
                .atZone(KST)
                .toOffsetDateTime();
    }

    private static LocalDate any() {
        return org.mockito.ArgumentMatchers.any(LocalDate.class);
    }
}
