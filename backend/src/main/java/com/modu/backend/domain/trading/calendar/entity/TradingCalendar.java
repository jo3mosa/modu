package com.modu.backend.domain.trading.calendar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 국내 거래일/휴장일 캐시 (trading_calendars 테이블) — S14P31B106-336
 *
 * KIS chk-holiday API (TR_ID=CTCA0903R) 응답을 1일 1회 갱신해 보관.
 * 자동매매 라우팅 시 정규장 / 예약주문 / reject 판단에 사용 — opnd_yn 기준.
 *
 * [필드 의미 — KIS 명세 그대로 보존]
 *  - bzdy_yn      영업일 여부 (은행 영업일 기준)
 *  - tr_day_yn    거래일 여부 (증권사 영업일)
 *  - opnd_yn      개장일 여부 (주식시장 개장 — 주문 가능 여부 판단 시 사용)
 *  - sttl_day_yn  결제일 여부
 *
 * Y/N 문자열을 그대로 보존하는 이유 — KIS 응답 의미를 추후 디버깅 시 1:1 매핑 가능하게 유지.
 * 사용 측면에선 isOpen() 헬퍼만 호출하면 됨.
 */
@Entity
@Table(name = "trading_calendars")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradingCalendar {

    private static final String Y = "Y";

    @Id
    @Column(name = "bass_dt")
    private LocalDate bassDt;

    @Column(name = "wday_dvsn_cd", nullable = false, length = 2)
    private String wdayDvsnCd;

    @Column(name = "bzdy_yn", nullable = false, length = 1)
    private String bzdyYn;

    @Column(name = "tr_day_yn", nullable = false, length = 1)
    private String trDayYn;

    @Column(name = "opnd_yn", nullable = false, length = 1)
    private String opndYn;

    @Column(name = "sttl_day_yn", nullable = false, length = 1)
    private String sttlDayYn;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Builder
    public TradingCalendar(LocalDate bassDt, String wdayDvsnCd,
                           String bzdyYn, String trDayYn, String opndYn, String sttlDayYn,
                           OffsetDateTime fetchedAt) {
        this.bassDt = bassDt;
        this.wdayDvsnCd = wdayDvsnCd;
        this.bzdyYn = bzdyYn;
        this.trDayYn = trDayYn;
        this.opndYn = opndYn;
        this.sttlDayYn = sttlDayYn;
        this.fetchedAt = fetchedAt;
    }

    /** 주식시장 개장일 여부 — 자동매매 라우팅 판단의 단일 진입점. */
    public boolean isOpen() {
        return Y.equals(opndYn);
    }

    /**
     * 동일 bass_dt row 재갱신 — KIS 가 휴장일 정보를 사후 수정한 경우 반영.
     * fetched_at 도 함께 갱신해 마지막 적재 시각 추적 가능.
     */
    public void refresh(String wdayDvsnCd, String bzdyYn, String trDayYn,
                        String opndYn, String sttlDayYn, OffsetDateTime fetchedAt) {
        this.wdayDvsnCd = wdayDvsnCd;
        this.bzdyYn = bzdyYn;
        this.trDayYn = trDayYn;
        this.opndYn = opndYn;
        this.sttlDayYn = sttlDayYn;
        this.fetchedAt = fetchedAt;
    }
}
