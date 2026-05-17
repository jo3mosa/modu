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
