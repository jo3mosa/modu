package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.client.KisCandleClient;
import com.modu.backend.domain.market.client.KisPriceClient;
import com.modu.backend.domain.market.dto.CandleListResponse;
import com.modu.backend.domain.market.dto.CandleResponse;
import com.modu.backend.domain.market.dto.NewsResponse;
import com.modu.backend.domain.market.dto.StockDetailResponse;
import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.entity.NewsArticle;
import com.modu.backend.domain.market.entity.StockMaster;
import com.modu.backend.domain.market.exception.MarketErrorCode;
import com.modu.backend.domain.market.repository.NewsArticleRepository;
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
    @Mock NewsArticleRepository newsArticleRepository;
    @Mock KisPlatformTokenService kisPlatformTokenService;
    @Mock KisPriceClient kisPriceClient;
    @Mock KisCandleClient kisCandleClient;
    @Mock MinuteCandleService minuteCandleService;

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

    // ── 캔들 데이터 조회 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("일봉 캔들 조회 성공 - 플랫폼 토큰과 stockCode, period가 클라이언트에 전달")
    void 일봉_캔들_조회_성공() {
        // given
        List<CandleResponse> mockCandles = List.of(
                new CandleResponse("20250426", 81500L, 83000L, 80500L, 82000L, 15000000L),
                new CandleResponse("20250425", 80000L, 82000L, 79500L, 81000L, 12000000L)
        );
        when(kisPlatformTokenService.getAccessToken()).thenReturn("platform-token");
        when(kisCandleClient.getDailyCandles("platform-token", "005930", "D", null, null))
                .thenReturn(mockCandles);

        // when
        CandleListResponse result = marketService.getCandleData("005930", "D", null, null);

        // then
        assertThat(result.stockCode()).isEqualTo("005930");
        assertThat(result.period()).isEqualTo("D");
        assertThat(result.candles()).hasSize(2);
        assertThat(result.candles().get(0).timestamp()).isEqualTo("20250426");
    }

    @Test
    @DisplayName("분봉 캔들 조회 — MinuteCandleService 경유")
    void 분봉_캔들_조회_성공() {
        // given
        List<CandleResponse> mockCandles = List.of(
                new CandleResponse("20260520090000", 81000L, 81500L, 80800L, 81200L, 500000L)
        );
        when(minuteCandleService.getMinuteCandles("005930", "5", null, null))
                .thenReturn(mockCandles);

        // when
        CandleListResponse result = marketService.getCandleData("005930", "5", null, null);

        // then
        assertThat(result.period()).isEqualTo("5");
        assertThat(result.candles()).hasSize(1);
        verify(minuteCandleService).getMinuteCandles("005930", "5", null, null);
    }

    @Test
    @DisplayName("캔들 데이터가 없는 종목 조회 시 빈 목록 반환")
    void 캔들_데이터_없으면_빈_목록() {
        // given
        when(kisPlatformTokenService.getAccessToken()).thenReturn("platform-token");
        when(kisCandleClient.getDailyCandles("platform-token", "005930", "D", null, null))
                .thenReturn(List.of());

        // when
        CandleListResponse result = marketService.getCandleData("005930", "D", null, null);

        // then
        assertThat(result.candles()).isEmpty();
    }

    @Test
    @DisplayName("startDate, endDate 있을 때 KIS 클라이언트에 정확히 전달")
    void 날짜_파라미터_전달_확인() {
        // given
        when(kisPlatformTokenService.getAccessToken()).thenReturn("platform-token");
        when(kisCandleClient.getDailyCandles("platform-token", "005930", "D", "20250101", "20250426"))
                .thenReturn(List.of());

        // when
        marketService.getCandleData("005930", "D", "20250101", "20250426");

        // then
        verify(kisCandleClient).getDailyCandles("platform-token", "005930", "D", "20250101", "20250426");
    }

    // ── 종목별 뉴스 조회 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("종목 뉴스 조회 성공 - source_name 한글 매핑 + KST +09:00 부착")
    void 종목_뉴스_조회_성공() {
        // given
        when(stockMasterRepository.findByStockCode("005930")).thenReturn(Optional.of(samsung));

        NewsArticle article = NewsArticle.builder()
                .id("hankyung_2026051789231")
                .title("삼성전자 1분기 영업이익 6.6조원 발표")
                .sourceName("한국경제")
                .publishedAt("2026-05-17T14:32:18")   // KST, 타임존 없음
                .url("https://www.hankyung.com/article/2026051789231")
                .stockCodes(List.of("005930"))
                .build();
        when(newsArticleRepository.findTop20ByStockCodesContainingOrderByPublishedAtDesc("005930"))
                .thenReturn(List.of(article));

        // when
        List<NewsResponse> result = marketService.getStockNews("005930");

        // then
        assertThat(result).hasSize(1);
        NewsResponse news = result.get(0);
        assertThat(news.title()).isEqualTo("삼성전자 1분기 영업이익 6.6조원 발표");
        assertThat(news.source()).isEqualTo("한국경제");
        assertThat(news.url()).isEqualTo("https://www.hankyung.com/article/2026051789231");
        // KST +09:00 부착 확인
        assertThat(news.publishedAt().toString()).isEqualTo("2026-05-17T14:32:18+09:00");
    }

    @Test
    @DisplayName("매칭 기사 없을 때 빈 리스트 반환 (404 아님)")
    void 매칭_기사_없으면_빈_리스트() {
        // given
        when(stockMasterRepository.findByStockCode("005930")).thenReturn(Optional.of(samsung));
        when(newsArticleRepository.findTop20ByStockCodesContainingOrderByPublishedAtDesc("005930"))
                .thenReturn(List.of());

        // when
        List<NewsResponse> result = marketService.getStockNews("005930");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("미등록 종목코드로 뉴스 조회 시 STOCK_NOT_FOUND 예외")
    void 미등록_종목_뉴스_조회_시_예외() {
        // given
        when(stockMasterRepository.findByStockCode("999999")).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> marketService.getStockNews("999999"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(MarketErrorCode.STOCK_NOT_FOUND));
    }
}
