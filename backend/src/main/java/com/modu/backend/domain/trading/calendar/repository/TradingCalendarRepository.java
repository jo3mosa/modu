package com.modu.backend.domain.trading.calendar.repository;

import com.modu.backend.domain.trading.calendar.entity.TradingCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * trading_calendars 접근 인터페이스 — S14P31B106-336
 *
 * 단일 row 조회는 PK (bass_dt) 검색이라 인덱스 hit 즉시 응답.
 * 다음 개장일 검색은 partial index (idx_trading_calendars_opnd_y) 활용.
 */
public interface TradingCalendarRepository extends JpaRepository<TradingCalendar, LocalDate> {

    /** 단일 일자 캐시 조회 — 자동매매 라우팅 단계에서 isMarketOpen 판단에 사용. */
    Optional<TradingCalendar> findByBassDt(LocalDate bassDt);

    /**
     * 지정 일자 이후(포함) 가장 빠른 개장일 — 예약주문 시 다음 영업일 표시 등에 사용.
     * partial index 활용 → opnd_yn='Y' row 만 스캔.
     */
    Optional<TradingCalendar> findFirstByBassDtGreaterThanEqualAndOpndYnOrderByBassDtAsc(
            LocalDate from, String opndYn);
}
