package com.modu.backend.domain.market.service;

import com.modu.backend.domain.market.dto.StockListResponse;
import com.modu.backend.domain.market.dto.StockSummaryResponse;
import com.modu.backend.domain.market.repository.StockMasterRepository;
import lombok.RequiredArgsConstructor;
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

    /**
     * 종목 전체 조회 또는 키워드 검색
     *
     * keyword 없음: 활성 종목 전체 페이징 조회
     * keyword 있음: 종목명 또는 종목코드 부분 일치 검색
     *
     * @param keyword 검색어 (null 또는 공백이면 전체 조회)
     * @param page    페이지 번호 (1부터 시작, 내부적으로 0 기반으로 변환)
     * @param size    페이지 크기
     */
    public StockListResponse getStocks(String keyword, int page, int size) {
        // stockCode 오름차순 정렬 고정: 정렬 없이 페이징 시 데이터 변경에 따른 중복/누락 방지
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

    private boolean isKeywordBlank(String keyword) {
        return keyword == null || keyword.isBlank();
    }
}
