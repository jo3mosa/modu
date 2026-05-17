package com.modu.backend.domain.market.dto;

import com.modu.backend.domain.market.entity.NewsArticle;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
     * MongoDB 의 published_at 은 타임존 없는 ISO 문자열(KST 기준)로 적재된다.
     * 응답 시 +09:00 을 명시적으로 부착해 클라이언트 타임존 해석 오차를 막는다.
     */
    public static NewsResponse from(NewsArticle doc) {
        OffsetDateTime published = doc.getPublishedAt() == null
                ? null
                : LocalDateTime.parse(doc.getPublishedAt()).atOffset(KST);
        return new NewsResponse(
                doc.getTitle(),
                doc.getSourceName(),
                published,
                doc.getUrl()
        );
    }
}
