package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.repository.StockRiskTierRedisRepository;
import com.modu.backend.domain.ai.service.StockRiskTierSyncService.SyncResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockRiskTierSyncServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StockRiskTierRedisRepository stockRiskTierRedisRepository;

    @InjectMocks
    private StockRiskTierSyncService service;

    @Test
    @DisplayName("syncAll - 정상 조회 시 saveBatch 호출 + success result")
    void syncAll_success() throws SQLException {
        ResultSet rs = mockResultSet(
                new String[]{"005930", "000660"},
                new int[]{3, 5}
        );
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            while (rs.next()) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        SyncResult result = service.syncAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Integer>> captor = ArgumentCaptor.forClass(Map.class);
        verify(stockRiskTierRedisRepository).saveBatch(captor.capture());
        assertThat(captor.getValue()).containsEntry("005930", 3).containsEntry("000660", 5);
        assertThat(result.success()).isTrue();
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("syncAll - 결과 0건이면 saveBatch 호출 X + empty result")
    void syncAll_emptyResult() {
        // 기본 mock: query 호출해도 handler 미호출 (0건)

        SyncResult result = service.syncAll();

        verify(stockRiskTierRedisRepository, never()).saveBatch(any());
        assertThat(result.success()).isTrue();
        assertThat(result.count()).isZero();
    }

    @Test
    @DisplayName("syncAll - saveBatch 예외 시 failed result (DB 조회 실패와 대칭)")
    void syncAll_saveBatchException_returnsFailed() throws SQLException {
        ResultSet rs = mockResultSet(new String[]{"005930"}, new int[]{3});
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            while (rs.next()) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
        doThrow(new RuntimeException("redis down"))
                .when(stockRiskTierRedisRepository).saveBatch(any());

        SyncResult result = service.syncAll();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("redis down");
    }

    @Test
    @DisplayName("syncAll - JDBC 예외 시 failed result + saveBatch 호출 X")
    void syncAll_jdbcException() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        SyncResult result = service.syncAll();

        verify(stockRiskTierRedisRepository, never()).saveBatch(any());
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("db down");
    }

    private static ResultSet mockResultSet(String[] codes, int[] tiers) throws SQLException {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        Boolean[] nextValues = new Boolean[codes.length + 1];
        for (int i = 0; i < codes.length; i++) nextValues[i] = true;
        nextValues[codes.length] = false;
        org.mockito.Mockito.when(rs.next()).thenReturn(nextValues[0],
                java.util.Arrays.copyOfRange(nextValues, 1, nextValues.length));
        org.mockito.Mockito.when(rs.getString("stock_code")).thenReturn(codes[0],
                java.util.Arrays.copyOfRange(codes, 1, codes.length));
        Integer[] boxed = java.util.Arrays.stream(tiers).boxed().toArray(Integer[]::new);
        org.mockito.Mockito.when(rs.getInt("risk_tier")).thenReturn(boxed[0],
                java.util.Arrays.copyOfRange(boxed, 1, boxed.length));
        return rs;
    }
}
