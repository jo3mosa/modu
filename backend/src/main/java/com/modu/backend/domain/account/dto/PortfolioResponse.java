package com.modu.backend.domain.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "사용자 포트폴리오 응답")
public record PortfolioResponse(
        @Schema(description = "보유 종목 목록")
        List<HoldingResponse> holdings
) {}
