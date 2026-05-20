package com.modu.backend.domain.market.repository;

import com.modu.backend.domain.market.dto.CandleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 분봉 캔들 영구 캐시 Repository — KIS 213 호출 결과 적재 + 조회.
 *
 * 멀티 pod 환경 ON CONFLICT DO NOTHING 으로 중복 INSERT 방어.
 * 일자 단위 적재 완료 마커(stock_minute_candle_loaded_date) 로 missing 일자 식별.
 */
@Repository
@RequiredArgsConstructor
public class StockMinuteCandleRepository {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String INSERT_CANDLE = """
            INSERT INTO stock_minute_candle
              (stock_code, ts, open_price, high_price, low_price, close_price, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stock_code, ts) DO NOTHING
            """;

    private static final String INSERT_LOADED_DATE = """
            INSERT INTO stock_minute_candle_loaded_date
              (stock_code, trade_date, candle_count)
            VALUES (?, ?, ?)
            ON CONFLICT (stock_code, trade_date) DO NOTHING
            """;

    private static final String SELECT_IN_RANGE = """
            SELECT ts, open_price, high_price, low_price, close_price, volume
            FROM stock_minute_candle
            WHERE stock_code = ?
              AND ts >= ?
              AND ts <= ?
            ORDER BY ts ASC
            """;

    private static final String SELECT_LOADED_DATES = """
            SELECT trade_date
            FROM stock_minute_candle_loaded_date
            WHERE stock_code = ?
              AND trade_date BETWEEN ? AND ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<CandleResponse> findInRange(String stockCode, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query(
                SELECT_IN_RANGE,
                ps -> {
                    ps.setString(1, stockCode);
                    ps.setTimestamp(2, Timestamp.valueOf(from));
                    ps.setTimestamp(3, Timestamp.valueOf(to));
                },
                (rs, rowNum) -> new CandleResponse(
                        rs.getTimestamp("ts").toLocalDateTime().format(TS_FMT),
                        rs.getLong("open_price"),
                        rs.getLong("high_price"),
                        rs.getLong("low_price"),
                        rs.getLong("close_price"),
                        rs.getLong("volume")
                )
        );
    }

    public Set<LocalDate> findLoadedDates(String stockCode, LocalDate from, LocalDate to) {
        List<LocalDate> dates = jdbcTemplate.query(
                SELECT_LOADED_DATES,
                ps -> {
                    ps.setString(1, stockCode);
                    ps.setObject(2, from);
                    ps.setObject(3, to);
                },
                (rs, rowNum) -> rs.getDate("trade_date").toLocalDate()
        );
        return new HashSet<>(dates);
    }

    public void saveCandles(String stockCode, List<CandleResponse> candles) {
        if (candles.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>(candles.size());
        for (CandleResponse c : candles) {
            LocalDateTime ts = LocalDateTime.parse(c.timestamp(), TS_FMT);
            batch.add(new Object[]{
                    stockCode,
                    Timestamp.valueOf(ts),
                    c.openPrice(),
                    c.highPrice(),
                    c.lowPrice(),
                    c.closePrice(),
                    c.volume()
            });
        }
        jdbcTemplate.batchUpdate(INSERT_CANDLE, batch);
    }

    public void markLoaded(String stockCode, LocalDate date, int candleCount) {
        jdbcTemplate.update(INSERT_LOADED_DATE, stockCode, date, candleCount);
    }
}
