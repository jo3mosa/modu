package com.modu.backend.domain.strategy.controller;

import com.modu.backend.domain.strategy.dto.ProfileQuestionListResponse;
import com.modu.backend.domain.strategy.dto.ProfileResponse;
import com.modu.backend.domain.strategy.dto.ProfileUpdateRequest;
import com.modu.backend.domain.strategy.dto.ProfileUpdateResponse;
import com.modu.backend.domain.strategy.service.StrategyProfileQuestionService;
import com.modu.backend.domain.strategy.service.StrategyProfileService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Strategy", description = "투자 전략 API")
@RestController
@RequestMapping("/api/v1/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyProfileQuestionService strategyProfileQuestionService;
    private final StrategyProfileService strategyProfileService;

    @Operation(
            summary = "투자 성향 설문 문항 조회",
            description = """
                    투자 성향 분석에 사용할 설문 문항과 선택지 목록을 조회합니다.

                    - 선택지별 점수는 응답에 노출하지 않습니다.
                    - 파생상품 등 투자경험 문항은 조회에는 포함하지만 점수 산정에서는 제외합니다.
                    - 손실수준 문항의 투자상품 제한 정책은 답변 제출 및 결과 산출 시 서버 내부 기준으로 처리합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설문 문항 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping("/profile-questions")
    public ResponseEntity<ApiResponse<ProfileQuestionListResponse>> getProfileQuestions() {
        ProfileQuestionListResponse response = strategyProfileQuestionService.getProfileQuestions();
        return ResponseEntity.ok(ApiResponse.success("설문 문항을 조회했습니다.", response));
    }

    @Operation(
            summary = "내 투자 성향 조회",
            description = """
                    인증된 사용자의 최신 투자 성향 프로필을 조회합니다.

                    - 투자 성향은 기존 5단계 등급을 그대로 반환합니다.
                    - 설문 답변과 자유 입력 투자 원칙은 investment_profiles.answers_snapshot 기준으로 반환합니다.
                    - 저장된 투자 성향 프로필이 없으면 INVEST_001 에러를 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "투자 성향 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "투자 성향 프로필 없음")
    })
    @GetMapping("/me/profiles")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @AuthenticationPrincipal Long userId) {

        ProfileResponse response = strategyProfileService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success("투자 성향을 조회했습니다.", response));
    }

    @Operation(
            summary = "투자 성향 입력/수정",
            description = """
                    투자 성향 설문 답변을 제출해 사용자 투자 성향을 저장하거나 수정합니다.

                    - 설문 답변은 questionId와 optionId 기준으로 검증합니다.
                    - 선택지별 점수는 서버 내부 기준으로 산정합니다.
                    - 파생상품 등 투자경험 문항은 답변 저장에는 포함하지만 점수 산정에서는 제외합니다.
                    - 점수는 `총점 * 100 / 72`로 환산한 뒤 5단계 투자 성향으로 분류합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "투자 성향 저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 설문 답변"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @PatchMapping("/me/profiles")
    public ResponseEntity<ApiResponse<ProfileUpdateResponse>> updateProfile(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid ProfileUpdateRequest request) {

        ProfileUpdateResponse response = strategyProfileService.updateProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success("투자 성향이 저장되었습니다.", response));
    }
}
