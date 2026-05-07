package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.client.KisPriceClient;
import com.modu.backend.domain.market.dto.StockDetailResponse;
import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.entity.StockMaster;
import com.modu.backend.domain.market.exception.MarketErrorCode;
import com.modu.backend.domain.market.repository.StockMasterRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock StockMasterRepository stockMasterRepository;
    @Mock KisPlatformTokenService kisPlatformTokenService;
    @Mock KisPriceClient kisPriceClient;

    @InjectMocks
    MarketService marketService;

    private StockMaster samsung;
    private StockMaster skhynix;

    @BeforeEach
    void setUp() {
        samsung = StockMaster.builder()
                .stockCode("005930")
                .stockName("삼성전자")
                .marketType("KOSPI")
                .isActive(true)
                .build();

        skhynix = StockMaster.builder()
                .stockCode("000660")
                .stockName("SK하이닉스")
                .marketType("KOSPI")
                .isActive(true)
                .build();
    }

    // ── 전체 조회 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keyword 없으면 활성 종목 전체 조회")
    void keyword_없으면_전체_조회() {
        // given
        when(stockMasterRepository.findByIsActiveTrue(any()))
                .thenReturn(new PageImpl<>(List.of(samsung, skhynix), PageRequest.of(0, 20), 2));

        // when
        StockListResponse result = marketService.getStocks(null, 1, 20);

        // then
        assertThat(result.stocks()).hasSize(2);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        verify(stockMasterRepository).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("빈 문자열 keyword도 전체 조회로 처리")
    void 빈_문자열_keyword_전체_조회() {
        // given
        when(stockMasterRepository.findByIsActiveTrue(any()))
                .thenReturn(new PageImpl<>(List.of(samsung), PageRequest.of(0, 20), 1));

        // when
        StockListResponse result = marketService.getStocks("   ", 1, 20);

        // then
        verify(stockMasterRepository).findByIsActiveTrue(any());
    }

    // ── 검색 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keyword 있으면 종목명/코드 검색")
    void keyword_있으면_검색() {
        // given
        when(stockMasterRepository.searchByKeyword(eq("삼성"), any()))
                .thenReturn(new PageImpl<>(List.of(samsung), PageRequest.of(0, 20), 1));

        // when
        StockListResponse result = marketService.getStocks("삼성", 1, 20);

        // then
        assertThat(result.stocks()).hasSize(1);
        assertThat(result.stocks().get(0).stockCode()).isEqualTo("005930");
        assertThat(result.stocks().get(0).stockName()).isEqualTo("삼성전자");
        assertThat(result.totalCount()).isEqualTo(1);
        verify(stockMasterRepository).searchByKeyword(eq("삼성"), any());
    }

    @Test
    @DisplayName("검색 결과 없으면 빈 목록 반환")
    void 검색_결과_없으면_빈_목록() {
        // given
        when(stockMasterRepository.searchByKeyword(eq("없는종목"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // when
        StockListResponse result = marketService.getStocks("없는종목", 1, 20);

        // then
        assertThat(result.stocks()).isEmpty();
        assertThat(result.totalCount()).isEqualTo(0);
    }

    // ── 페이징 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("page 파라미터가 1부터 시작해도 0 기반으로 변환")
    void page_1부터_시작_변환_확인() {
        // given
        PageRequest expectedPageable = PageRequest.of(1, 10, Sort.by("stockCode").ascending());
        when(stockMasterRepository.findByIsActiveTrue(eq(expectedPageable)))
                .thenReturn(new PageImpl<>(List.of(), expectedPageable, 25));

        // when
        StockListResponse result = marketService.getStocks(null, 2, 10);

        // then
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        verify(stockMasterRepository).findByIsActiveTrue(eq(expectedPageable));
    }

    @Test
    @DisplayName("응답 DTO에 stockCode, stockName, marketType 정상 매핑")
    void DTO_필드_매핑_확인() {
        // given
        when(stockMasterRepository.findByIsActiveTrue(any()))
                .thenReturn(new PageImpl<>(List.of(samsung), PageRequest.of(0, 20), 1));

        // when
        StockListResponse result = marketService.getStocks(null, 1, 20);

        // then
        assertThat(result.stocks().get(0).stockCode()).isEqualTo("005930");
        assertThat(result.stocks().get(0).stockName()).isEqualTo("삼성전자");
        assertThat(result.stocks().get(0).marketType()).isEqualTo("KOSPI");
    }

    // ── 종목 상세 조회 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("종목 상세 조회 성공 - KIS API 호출 후 응답 반환")
    void 종목_상세_조회_성공() {
        // given
        when(stockMasterRepository.findByStockCode("005930")).thenReturn(Optional.of(samsung));
        when(kisPlatformTokenService.getAccessToken()).thenReturn("platform-token");

        StockDetailResponse mockDetail = new StockDetailResponse(
                "005930", "삼성전자", "KOSPI",
                82000L, 1.23, "2", 15000000L,
                489000000000000L, 81500L, 83000L, 81000L
        );
        when(kisPriceClient.getStockDetail("platform-token", "005930", "삼성전자", "KOSPI"))
                .thenReturn(mockDetail);

        // when
        StockDetailResponse result = marketService.getStockDetail("005930");

        // then
        assertThat(result.stockCode()).isEqualTo("005930");
        assertThat(result.stockName()).isEqualTo("삼성전자");
        assertThat(result.currentPrice()).isEqualTo(82000L);
        assertThat(result.compareRate()).isEqualTo(1.23);
        assertThat(result.marketCap()).isEqualTo(489000000000000L);
    }

    @Test
    @DisplayName("존재하지 않는 종목코드로 상세 조회 시 STOCK_NOT_FOUND 예외")
    void 존재하지_않는_종목코드_상세_조회_시_예외() {
        // given
        when(stockMasterRepository.findByStockCode("999999")).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> marketService.getStockDetail("999999"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(MarketErrorCode.STOCK_NOT_FOUND));
    }

    @Test
    @DisplayName("종목 상세 조회 시 플랫폼 토큰과 종목 정보가 KIS 클라이언트에 올바르게 전달")
    void 종목_상세_조회_KIS_클라이언트_호출_확인() {
        // given
        when(stockMasterRepository.findByStockCode("005930")).thenReturn(Optional.of(samsung));
        when(kisPlatformTokenService.getAccessToken()).thenReturn("platform-token");
        when(kisPriceClient.getStockDetail(any(), any(), any(), any()))
                .thenReturn(new StockDetailResponse(
                        "005930", "삼성전자", "KOSPI",
                        82000L, 1.23, "2", 15000000L,
                        489000000000000L, 81500L, 83000L, 81000L
                ));

        // when
        marketService.getStockDetail("005930");

        // then
        verify(kisPriceClient).getStockDetail("platform-token", "005930", "삼성전자", "KOSPI");
    }
}
