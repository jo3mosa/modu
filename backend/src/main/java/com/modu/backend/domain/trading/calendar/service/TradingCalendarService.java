package com.modu.backend.domain.trading.calendar.service;

import com.modu.backend.domain.trading.calendar.entity.TradingCalendar;
import com.modu.backend.domain.trading.calendar.repository.TradingCalendarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 거래일/휴장일 조회 + 캐시 갱신 서비스 — S14P31B106-336
 *
 * [읽기 — 자동매매 라우팅 진입점]
 *  isMarketOpen(date) 가 단일 SoT. row 결손 시 월~금 영업일 가정 fallback + WARN 로그.
 *  fallback 은 공휴일을 못 잡지만 캐시 갱신 1~2회 누락 상황에서 시스템 정지 회피용.
 *
 * [쓰기 — 갱신 스케줄러 / 운영자 수동 갱신]
 *  upsert 는 JPA save 의 merge 동작 활용. PK (bass_dt) 단위 단건 저장.
 *  bulk 호출 시 트랜잭션 안에서 saveAll — 약 90건 / 갱신 → 부하 작음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingCalendarService {

    private final TradingCalendarRepository repository;

    // ───────────────────────────────────────────────────────────────────
    // 읽기
    // ───────────────────────────────────────────────────────────────────

    /**
     * 주식시장 개장일 여부.
     *
     * 캐시 row 결손 시 fallback — 월~금 true, 토/일 false. 공휴일 미반영이라
     * 운영 시 캐시 갱신 누락이 길어지면 잘못된 판단 가능 → WARN 로그 + 알람 필수.
     */
    @Transactional(readOnly = true)
    public boolean isMarketOpen(LocalDate date) {
        Optional<TradingCalendar> cached = repository.findByBassDt(date);
        if (cached.isPresent()) {
            return cached.get().isOpen();
        }
        boolean weekdayFallback = date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        log.warn("trading_calendars 캐시 결손 — fallback 적용 - date: {}, fallback(isOpen): {}",
                date, weekdayFallback);
        return weekdayFallback;
    }

    /**
     * from(포함) 이후 가장 빠른 개장일. 캐시에 row 가 없으면 Optional.empty.
     * UI 표기 (다음 영업일 안내) 또는 RESERVED_PENDING 처리 시 대기 만료 판단에 활용.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> findNextMarketOpenDate(LocalDate from) {
        return repository
                .findFirstByBassDtGreaterThanEqualAndOpndYnOrderByBassDtAsc(from, "Y")
                .map(TradingCalendar::getBassDt);
    }

    // ───────────────────────────────────────────────────────────────────
    // 쓰기 — 갱신
    // ───────────────────────────────────────────────────────────────────

    /**
     * KIS 응답을 받아 일괄 upsert. 동일 bass_dt 가 있으면 entity.refresh() 로
     * 컬럼 갱신 — 신규/변경 모두 같은 메서드로 처리.
     *
     * 트랜잭션 안에서 batch — 일부 row INSERT 실패 시 전체 롤백 (스케줄러가 재시도 또는 알람).
     */
    @Transactional
    public int upsertAll(List<TradingCalendar> incoming) {
        int upserted = 0;
        for (TradingCalendar in : incoming) {
            Optional<TradingCalendar> existing = repository.findByBassDt(in.getBassDt());
            if (existing.isPresent()) {
                existing.get().refresh(
                        in.getWdayDvsnCd(), in.getBzdyYn(), in.getTrDayYn(),
                        in.getOpndYn(), in.getSttlDayYn(), in.getFetchedAt());
            } else {
                repository.save(in);
            }
            upserted++;
        }
        log.info("trading_calendars upsert 완료 - count: {}", upserted);
        return upserted;
    }

    /** 캐시 비어있는지 — ApplicationRunner 부팅 시 즉시 갱신 트리거 판단용. */
    @Transactional(readOnly = true)
    public boolean isCacheEmpty() {
        return repository.count() == 0L;
    }
}
