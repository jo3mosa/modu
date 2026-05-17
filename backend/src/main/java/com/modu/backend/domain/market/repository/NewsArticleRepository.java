package com.modu.backend.domain.market.repository;

import com.modu.backend.domain.market.entity.NewsArticle;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NewsArticleRepository extends MongoRepository<NewsArticle, String> {

    /**
     * 종목코드가 stock_codes 배열에 포함된 기사를 published_at 내림차순으로 최대 20건 조회.
     * stock_published 복합 인덱스({stock_codes:1, published_at:-1}) 활용.
     */
    List<NewsArticle> findTop20ByStockCodesContainingOrderByPublishedAtDesc(String stockCode);
}
