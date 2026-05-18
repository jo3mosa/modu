/**
 * AI 에이전트 관련 API 함수
 * 베이스 경로: /api/v1/ai-agent
 *
 * 어댑터 레이어:
 * - 백엔드 raw 응답(AiJudgmentSummaryResponse 등) → 프론트 표준 형식으로 변환
 * - 표준 형식은 두 차원으로 분리: action(BUY/SELL/HOLD) + executionStatus(READY/APPROVAL_REQUIRED/NONE/BLOCKED)
 * - 백엔드가 추후 action·executionStatus를 분리 응답하기 시작하면 adaptSummary 함수 한 곳만 수정하면 됨
 */
import apiClient from './apiClient';

/**
 * 화면 표시용 라벨/색상 매핑.
 * 화면 컴포넌트에서 ACTION_DISPLAY[log.action] 형태로 사용.
 */
export const ACTION_DISPLAY = {
  BUY:     { label: '매수', color: '#ef4444' },
  SELL:    { label: '매도', color: '#3b82f6' },
  HOLD:    { label: '관망', color: '#888' },
  UNKNOWN: { label: '판단', color: '#aaa' },
};

export const EXECUTION_STATUS_DISPLAY = {
  READY:             { label: '실행됨',   color: '#84cc16' },
  APPROVAL_REQUIRED: { label: '승인필요', color: '#eab308' },
  NONE:              { label: '미실행',   color: '#888' },
  BLOCKED:           { label: '차단',     color: '#ef4444' },
};

/**
 * 백엔드 eventType → executionStatus 매핑.
 * 백엔드가 PASSED/HOLD/BLOCKED/APPROVAL_REQUIRED를 사용 중이며, PASSED가 사실상 READY 의미.
 */
function mapExecutionStatus(eventType) {
  if (eventType === 'PASSED') return 'READY';
  if (eventType === 'HOLD') return 'NONE';
  if (eventType === 'BLOCKED') return 'BLOCKED';
  if (eventType === 'APPROVAL_REQUIRED') return 'APPROVAL_REQUIRED';
  return eventType;
}

/**
 * 백엔드 응답에서 action 추론.
 * 현재 응답에는 action 정보가 직접 없음 → HOLD만 추론 가능.
 * 백엔드가 action 필드를 분리해 보내기 시작하면 item.action을 그대로 쓰도록 변경.
 */
function inferAction(item) {
  if (item.action) return item.action; // 백엔드 분리 후 대비
  if (item.eventType === 'HOLD') return 'HOLD';
  return 'UNKNOWN';
}

/**
 * AI 판단 이력 항목(요약) → 프론트 표준 형식
 */
function adaptSummary(item) {
  return {
    id: item.judgmentId,
    orderId: item.orderId,
    stockCode: item.stockCode,
    action: inferAction(item),
    executionStatus: mapExecutionStatus(item.eventType),
    confidence: item.confidenceScore,
    reason: item.judgmentReason,
    decidedAt: item.judgedAt,
  };
}

/**
 * AI 판단 단건 상세 → 프론트 표준 형식
 */
function adaptDetail(item) {
  return {
    id: item.judgmentId,
    orderId: item.orderId,
    stockCode: item.stockCode,
    action: inferAction(item),
    executionStatus: mapExecutionStatus(item.eventType),
    confidence: item.confidenceScore,
    indicatorsSnapshot: item.indicatorsSnapshot ?? null,
    reason: item.judgmentReason,
    decidedAt: item.judgedAt,
  };
}

/**
 * AI 판단 전체 이력 조회 (페이징)
 * GET /api/v1/ai-agent/decisions?page={page}&size={size}
 *
 * @param {{ page?: number, size?: number }} [params]
 * @returns {Promise<{
 *   content: Array<{
 *     id: number,
 *     orderId: number | null,
 *     stockCode: string,
 *     action: 'BUY' | 'SELL' | 'HOLD' | 'UNKNOWN',
 *     executionStatus: 'READY' | 'APPROVAL_REQUIRED' | 'NONE' | 'BLOCKED',
 *     confidence: number,
 *     reason: string,
 *     decidedAt: string
 *   }>,
 *   page: number,
 *   size: number,
 *   totalElements: number,
 *   totalPages: number,
 *   hasNext: boolean
 * }>}
 */
export async function getAiDecisions({ page, size, stockCode } = {}) {
  const search = new URLSearchParams();
  if (page != null) search.set('page', String(page));
  if (size != null) search.set('size', String(size));
  if (stockCode) search.set('stockCode', stockCode);
  const queryString = search.toString();
  const endpoint = queryString ? `/ai-agent/decisions?${queryString}` : '/ai-agent/decisions';
  const data = await apiClient(endpoint);
  const raw = data.data ?? {};
  return {
    content: (raw.content ?? []).map(adaptSummary),
    page: raw.page ?? 0,
    size: raw.size ?? 0,
    totalElements: raw.totalElements ?? 0,
    totalPages: raw.totalPages ?? 0,
    hasNext: raw.hasNext ?? false,
  };
}

/**
 * 주문별 AI 판단 근거 조회
 * GET /api/v1/ai-agent/decisions/orders/{orderId}
 *
 * 연결된 AI 판단이 없으면 404 (errorCode: AI_001) 반환.
 *
 * @param {string | number} orderId
 * @returns {Promise<{
 *   id: number,
 *   orderId: number,
 *   stockCode: string,
 *   action: 'BUY' | 'SELL' | 'HOLD' | 'UNKNOWN',
 *   executionStatus: 'READY' | 'APPROVAL_REQUIRED' | 'NONE' | 'BLOCKED',
 *   confidence: number,
 *   indicatorsSnapshot: object | null,
 *   reason: string,
 *   decidedAt: string
 * }>}
 */
export async function getAiDecisionByOrder(orderId) {
  const data = await apiClient(`/ai-agent/decisions/orders/${orderId}`);
  return adaptDetail(data.data ?? {});
}

/**
 * 사용자 승인 대기(APPROVAL_REQUIRED) AI 판단 목록 조회.
 * GET /api/v1/ai-agent/decisions/pending
 *
 * 응답 항목 (PendingDecisionResponse — 백엔드 raw 그대로):
 * - id (Long), stockCode, decision (BUY/SELL/HOLD)
 * - orderAmount, targetPrice, stopLossPrice
 * - reasonSummary, riskLevel ('high' 등), confidenceScore
 * - judgedAt (ISO), approvalExpiresAt (ISO — 보통 judgedAt + 5분)
 */
export async function getPendingDecisions() {
  const data = await apiClient('/ai-agent/decisions/pending');
  // 백엔드 응답 형식이 배열 직접인지 { content: [] }인지 미확정 — 둘 다 대응
  const raw = data.data;
  if (Array.isArray(raw)) return raw;
  return raw?.content ?? raw?.items ?? [];
}

/**
 * AI 판단 승인 — READY 전환 후 주문 발행
 * POST /api/v1/ai-agent/decisions/{judgmentId}/approve
 *
 * 에러:
 * - 403 DECISION_FORBIDDEN: 본인 판단 아님
 * - 409 DECISION_NOT_PENDING: 이미 처리된 판단
 * - 410 DECISION_EXPIRED: 5분 만료됨
 *
 * @returns {Promise<{ judgmentId: number, executionStatus: 'READY', orderId: number }>}
 */
export async function approveDecision(judgmentId) {
  const data = await apiClient(`/ai-agent/decisions/${judgmentId}/approve`, {
    method: 'POST',
  });
  return data.data;
}

/**
 * AI 판단 거부 — REJECTED 전환, 주문 발행 없음
 * POST /api/v1/ai-agent/decisions/{judgmentId}/reject
 *
 * 에러는 approve와 동일 (403/409/410)
 *
 * @returns {Promise<{ judgmentId: number, executionStatus: 'REJECTED', orderId: null }>}
 */
export async function rejectDecision(judgmentId) {
  const data = await apiClient(`/ai-agent/decisions/${judgmentId}/reject`, {
    method: 'POST',
  });
  return data.data;
}

/**
 * AI 에이전트 메시지 조회 (복합 커서 페이지네이션)
 * GET /api/v1/ai-agent/messages
 *
 * BE 가 에이전트별로 발화 1건씩 영속화하므로, 채널 진입 시 본 함수로 과거 대화를 불러오고
 * 이후 메시지는 SSE 'agent-message' 이벤트로 실시간 수신한다.
 *
 * 페이지네이션:
 *   - 첫 페이지: { stockCode, size } — before/beforeId 미전달
 *   - 다음 페이지: 응답의 nextCursor/nextCursorId 를 그대로 before/beforeId 로 전달
 *   - nextCursor 가 null 이면 마지막 페이지
 *   - before 와 beforeId 는 항상 쌍으로 전달 (하나만 보내면 400)
 *
 * 정렬: created_at DESC, id DESC (최신 → 과거)
 *
 * @param {{ stockCode: string, before?: string, beforeId?: number, size?: number }} params
 * @returns {Promise<{
 *   content: Array<{
 *     messageId: number,
 *     stockCode: string,
 *     judgmentId: number | null,
 *     agent: 'BULL' | 'BEAR' | 'STRATEGY' | 'DECIDE',
 *     seq: number,
 *     text: string,
 *     createdAt: string
 *   }>,
 *   nextCursor: string | null,
 *   nextCursorId: number | null,
 *   hasMore: boolean
 * }>}
 */
export async function getAgentMessages({ stockCode, before, beforeId, size } = {}) {
  if (!stockCode) throw new Error('stockCode 는 필수입니다.');
  if ((before == null) !== (beforeId == null)) {
    throw new Error('before 와 beforeId 는 함께 전달해야 합니다.');
  }
  const search = new URLSearchParams();
  search.set('stockCode', stockCode);
  if (before) search.set('before', before);
  if (beforeId != null) search.set('beforeId', String(beforeId));
  if (size != null) search.set('size', String(size));
  const data = await apiClient(`/ai-agent/messages?${search.toString()}`);
  return data.data ?? { content: [], nextCursor: null, nextCursorId: null, hasMore: false };
}
