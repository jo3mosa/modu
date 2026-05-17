package com.modu.backend.domain.market.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

/**
 * 뉴스 기사 도큐먼트 (MongoDB modu_mongo.news_articles)
 *
 * analysis_server (news_collector.py / past_news_crawler.py) 가 적재한 RSS·백필 데이터.
 * 백엔드는 읽기 전용으로 조회만 수행.
 *
 * 인덱스 stock_published 는 종목별 최신순 조회용 — auto-index-creation:true 로
 * 부팅 시 보장된다. analysis_server 측 ensure_indexes 와 정의 중복돼도 동일 정의면 noop.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "news_articles")
@CompoundIndex(name = "stock_published", def = "{'stock_codes': 1, 'published_at': -1}")
public class NewsArticle {

    @Id
    private String id;

    private String title;

    @Field("source_name")
    private String sourceName;

    /**
     * ISO 문자열 ("2026-05-17T14:32:18") — KST 기준. 타임존 표기는 없음.
     * 응답 시 +09:00 부착 후 OffsetDateTime 으로 변환한다.
     */
    @Field("published_at")
    private String publishedAt;

    private String url;

    /**
     * 매칭된 종목코드 리스트 (analysis_server 의 news_collector.match_stocks 결과).
     * 쿼리 조건에만 사용 — 응답에는 노출하지 않는다.
     */
    @Field("stock_codes")
    private List<String> stockCodes;
}
