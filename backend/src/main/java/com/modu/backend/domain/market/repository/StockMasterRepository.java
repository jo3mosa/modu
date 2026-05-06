package com.modu.backend.domain.market.repository;

import com.modu.backend.domain.market.entity.StockMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 종목 마스터 레포지토리
 *
 * - 전체 조회: 활성 종목만 반환 (is_active = true)
 * - 검색: 종목명 또는 종목코드 부분 일치 (대소문자 무시)
 */
public interface StockMasterRepository extends JpaRepository<StockMaster, Long> {

    /** 활성 종목 전체 조회 (페이징) */
    Page<StockMaster> findByIsActiveTrue(Pageable pageable);

    /** 종목명 또는 종목코드로 활성 종목 검색 (페이징) */
    @Query("SELECT s FROM StockMaster s " +
            "WHERE s.isActive = true " +
            "AND (LOWER(s.stockName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR s.stockCode LIKE CONCAT('%', :keyword, '%'))")
    Page<StockMaster> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
