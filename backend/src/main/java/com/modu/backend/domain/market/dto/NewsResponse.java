package com.modu.backend.domain.market.dto;

import com.modu.backend.domain.market.entity.NewsArticle;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

@Schema(description = "종목 관련 뉴스 응답")
public record NewsResponse(
        @Schema(description = "기사 제목", example = "삼성전자 1분기 영업이익 6.6조원 발표")
        String title,

        @Schema(description = "출처 한글명", example = "한국경제")
        String source,

        @Schema(description = "발행 시각 (KST, ISO-8601)", example = "2026-05-17T14:32:18+09:00")
        OffsetDateTime publishedAt,

        @Schema(description = "원문 URL", example = "https://www.hankyung.com/article/2026051789231")
        String url
) {
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /**
     * published_at 은 타임존 없는 KST 기준 로컬 시각으로 적재되는데,
     * 수집 경로에 따라 구분자가 "T"(ISO) 또는 공백("yyyy-MM-dd HH:mm:ss") 으로 혼재한다.
     * 둘 다 허용하도록 구분자를 optional 로 둔 포맷터로 파싱한다.
     */
    private static final DateTimeFormatter PUBLISHED_AT_FORMAT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    /**
     * MongoDB 의 published_at 을 +09:00 을 명시적으로 부착해 응답함으로써
     * 클라이언트 타임존 해석 오차를 막는다.
     *
     * title 은 RSS 수집(news_collector.py) 경로 데이터가 &quot; / &amp; 등의 HTML
     * entity 를 그대로 담고 있을 수 있어 응답 시점에 디코딩한다.
     */
    public static NewsResponse from(NewsArticle doc) {
        OffsetDateTime published = doc.getPublishedAt() == null
                ? null
                : LocalDateTime.parse(doc.getPublishedAt(), PUBLISHED_AT_FORMAT).atOffset(KST);
        String title = doc.getTitle() == null ? null : HtmlUtils.htmlUnescape(doc.getTitle());
        return new NewsResponse(
                title,
                doc.getSourceName(),
                published,
                doc.getUrl()
        );
    }
}
