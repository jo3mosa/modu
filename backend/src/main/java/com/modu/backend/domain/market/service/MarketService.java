package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.client.KisCandleClient;
import com.modu.backend.domain.market.client.KisPriceClient;
import com.modu.backend.domain.market.dto.CandleListResponse;
import com.modu.backend.domain.market.dto.CandleResponse;
import com.modu.backend.domain.market.dto.NewsResponse;
import com.modu.backend.domain.market.dto.StockDetailResponse;
import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.dto.StockSummaryResponse;
import com.modu.backend.domain.market.entity.StockMaster;
import com.modu.backend.domain.market.exception.MarketErrorCode;
import com.modu.backend.domain.market.repository.NewsArticleRepository;
import com.modu.backend.domain.market.repository.StockMasterRepository;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종목 마스터 서비스
 *
 * stock_master 테이블은 DE 팀의 master_data_loader.py가 관리
 * 백엔드는 읽기 전용으로 조회만 수행
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketService {

    private final StockMasterRepository stockMasterRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final KisPlatformTokenService kisPlatformTokenService;
    private final KisPriceClient kisPriceClient;
    private final KisCandleClient kisCandleClient;

    /**
     * 종목 전체 조회 또는 키워드 검색
     *
     * keyword 없음: 활성 종목 전체 페이징 조회
     * keyword 있음: 종목명 또는 종목코드 부분 일치 검색
     */
    public StockListResponse getStocks(String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by("stockCode").ascending());

        Page<StockSummaryResponse> result = isKeywordBlank(keyword)
                ? stockMasterRepository.findByIsActiveTrue(pageable).map(StockSummaryResponse::from)
                : stockMasterRepository.searchByKeyword(keyword.trim(), pageable).map(StockSummaryResponse::from);

        return new StockListResponse(
                result.getContent(),
                result.getTotalElements(),
                page,
                size
        );
    }

    /**
     * 종목 상세 조회 (실시간 시세 포함)
     *
     * 1. stock_master에서 종목명/시장구분 조회 (없으면 STOCK_NOT_FOUND)
     * 2. KIS 시세 API 호출로 실시간 가격 데이터 조회
     * 3. Redis "stock:price" 캐시 TTL 3초 적용
     */
    @Cacheable(value = "stock:price", key = "#stockCode")
    public StockDetailResponse getStockDetail(String stockCode) {
        StockMaster stock = stockMasterRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new ApiException(MarketErrorCode.STOCK_NOT_FOUND));

        String accessToken = kisPlatformTokenService.getAccessToken();

        return kisPriceClient.getStockDetail(
                accessToken,
                stockCode,
                stock.getStockName(),
                stock.getMarketType()
        );
    }

    /**
     * 차트 캔들 데이터 조회
     *
     * period에 따라 KIS API를 자동 선택
     * - D/W/M: 기간별시세 API (FHKST03010100)
     * - 1/5/60: 당일분봉(FHKST03010200) 또는 일별분봉(FHKST03010230)
     */
    public CandleListResponse getCandleData(String stockCode, String period,
                                             String startDate, String endDate) {
        String accessToken = kisPlatformTokenService.getAccessToken();
        List<CandleResponse> candles = kisCandleClient.getCandles(
                accessToken, stockCode, period, startDate, endDate);

        return new CandleListResponse(stockCode, period, candles);
    }

    /**
     * 종목별 관련 뉴스 조회
     *
     * 1. stock_master에서 종목코드 유효성 확인 (없으면 STOCK_NOT_FOUND)
     * 2. MongoDB news_articles 에서 stock_codes 매칭 + published_at desc 최대 20건 조회
     * 3. 응답 DTO로 변환 (source_name → source, published_at 에 KST +09:00 부착)
     *
     * 매칭 기사가 없으면 빈 리스트 반환 (404 아님).
     */
    public List<NewsResponse> getStockNews(String stockCode) {
        stockMasterRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new ApiException(MarketErrorCode.STOCK_NOT_FOUND));

        return newsArticleRepository
                .findTop20ByStockCodesContainingOrderByPublishedAtDesc(stockCode)
                .stream()
                .map(NewsResponse::from)
                .toList();
    }

    private boolean isKeywordBlank(String keyword) {
        return keyword == null || keyword.isBlank();
    }
}
