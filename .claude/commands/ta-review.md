---
description: TradingAgents 전문가 관점으로 MODU AI 에이전트 코드를 리뷰. 인자가 없으면 현재 브랜치의 develop 대비 변경분 전체를 리뷰.
argument-hint: [파일경로 또는 디렉토리 | 비우면 현재 브랜치 diff]
---

너는 사용자의 `/ta-review $ARGUMENTS` 요청을 받았다.

`ta-reviewer` 서브에이전트를 Agent 툴로 호출해 리뷰를 위임하라. 너 자신이 직접 리뷰하지 말 것.

## 호출 규칙

**인자 처리:**
- `$ARGUMENTS`가 비어 있으면: 리뷰 대상 = "현재 브랜치(`develop` 대비)의 모든 AI 에이전트 관련 변경"
- `$ARGUMENTS`가 파일/디렉토리 경로면: 그 경로를 리뷰 대상으로 전달
- `$ARGUMENTS`가 그 외 자연어면: 그 내용을 컨텍스트로 그대로 전달

**Agent 툴 호출:**
- `subagent_type`: `ta-reviewer`
- `description`: "TA 전문가 리뷰: <대상 요약>"
- `prompt`: 아래 템플릿 사용. 절대 빈약하게 적지 말고, 서브에이전트가 컨텍스트 없이도 시작할 수 있게 다 적어줘라.

```
다음 변경을 TradingAgents 전문가 관점으로 리뷰해라.

## 리뷰 대상
<인자 또는 "현재 브랜치 develop 대비 변경분 전체">

## 컨텍스트
- 리포지토리: MODU (한국 주식 KIS 실거래 AI 에이전트)
- 현재 브랜치: <git rev-parse --abbrev-ref HEAD 결과>
- 메인 브랜치: develop
- 사용자 요청 원문: "/ta-review $ARGUMENTS"

## 절대 규칙
1. 시작 전에 `ai_agent/docs/tradingagents.md`와 `CLAUDE.md`를 반드시 Read해라.
2. 리뷰 대상 파일도 전체 Read해라 (요약/발췌 X).
3. 출력 형식은 너의 시스템 프롬프트 §4를 정확히 따라라.
4. 모든 발견에 `path:line`과 출처 태그를 붙여라.
```

## 호출 후

서브에이전트의 응답을 사용자에게 그대로 전달하되, 맨 앞에 한 줄로 무엇을 리뷰했는지 짧게 적어라. 예: "현재 브랜치(`feat/.../...`)의 ai_agent/ 변경분을 TA 전문가가 리뷰했습니다 ↓"

서브에이전트가 "리뷰할 변경이 없다"고 응답하면 사용자에게 그대로 알리고 멈춰라.
