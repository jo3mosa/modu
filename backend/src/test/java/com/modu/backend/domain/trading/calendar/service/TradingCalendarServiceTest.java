package com.modu.backend.domain.trading.calendar.service;

import com.modu.backend.domain.trading.calendar.entity.TradingCalendar;
import com.modu.backend.domain.trading.calendar.repository.TradingCalendarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * TradingCalendarService — 캐시 조회 + 결손 fallback 검증.
 */
@ExtendWith(MockitoExtension.class)
class TradingCalendarServiceTest {

    @Mock TradingCalendarRepository repository;

    @InjectMocks
    TradingCalendarService service;

    @Test
    @DisplayName("캐시 hit + opnd_yn=Y → true")
    void cachedOpen() {
        LocalDate date = LocalDate.of(2026, 5, 20);
        when(repository.findByBassDt(date)).thenReturn(Optional.of(open(date)));

        assertThat(service.isMarketOpen(date)).isTrue();
    }

    @Test
    @DisplayName("캐시 hit + opnd_yn=N → false")
    void cachedClosed() {
        LocalDate date = LocalDate.of(2026, 5, 23); // 토요일
        when(repository.findByBassDt(date)).thenReturn(Optional.of(closed(date)));

        assertThat(service.isMarketOpen(date)).isFalse();
    }

    @Test
    @DisplayName("캐시 결손 + 평일 → fallback true")
    void fallbackWeekday() {
        LocalDate wednesday = LocalDate.of(2026, 5, 20);
        when(repository.findByBassDt(wednesday)).thenReturn(Optional.empty());

        assertThat(service.isMarketOpen(wednesday)).isTrue();
    }

    @Test
    @DisplayName("캐시 결손 + 토요일 → fallback false")
    void fallbackSaturday() {
        LocalDate saturday = LocalDate.of(2026, 5, 23);
        when(repository.findByBassDt(saturday)).thenReturn(Optional.empty());

        assertThat(service.isMarketOpen(saturday)).isFalse();
    }

    @Test
    @DisplayName("캐시 결손 + 일요일 → fallback false")
    void fallbackSunday() {
        LocalDate sunday = LocalDate.of(2026, 5, 24);
        when(repository.findByBassDt(sunday)).thenReturn(Optional.empty());

        assertThat(service.isMarketOpen(sunday)).isFalse();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────

    private static TradingCalendar open(LocalDate date) {
        return TradingCalendar.builder()
                .bassDt(date)
                .wdayDvsnCd("04")
                .bzdyYn("Y").trDayYn("Y").opndYn("Y").sttlDayYn("Y")
                .fetchedAt(OffsetDateTime.now())
                .build();
    }

    private static TradingCalendar closed(LocalDate date) {
        return TradingCalendar.builder()
                .bassDt(date)
                .wdayDvsnCd("06")
                .bzdyYn("N").trDayYn("Y").opndYn("N").sttlDayYn("N")
                .fetchedAt(OffsetDateTime.now())
                .build();
    }
}
