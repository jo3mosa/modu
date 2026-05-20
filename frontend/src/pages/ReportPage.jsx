import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { useLocation } from 'react-router-dom';
import {
  getAiDecisions,
  getAiDecisionByOrder,
  ACTION_DISPLAY,
  EXECUTION_STATUS_DISPLAY,
} from '../api/aiAgent';
import { getOrderHistory, ORDER_STATUS_DISPLAY } from '../api/order';

import './ReportPage.css';

// API 통신 실패나 빈 데이터 상태 시 폴백으로 보여줄 매매 이력 모의 데이터
const MOCK_GENERAL_HISTORY = [
  {
    orderId: 'manual-1001',
    source: 'MANUAL',
    stockCode: '005930',
    stockName: '삼성전자',
    side: 'BUY',
    price: 78500,
    quantity: 10,
    status: 'FILLED',
    createdAt: '2026-05-20T09:30:15Z'
  },
  {
    orderId: 'manual-1002',
    source: 'MANUAL',
    stockCode: '000660',
    stockName: 'SK하이닉스',
    side: 'SELL',
    price: 182300,
    quantity: 5,
    status: 'FILLED',
    createdAt: '2026-05-20T10:15:30Z'
  },
  {
    orderId: 'manual-1003',
    source: 'MANUAL',
    stockCode: '035720',
    stockName: '카카오',
    side: 'BUY',
    price: 48900,
    quantity: 20,
    status: 'FILLED',
    createdAt: '2026-05-20T11:05:45Z'
  }
];

// 매매 이력 섹션 페이지당 표시 개수 (클라이언트 페이지네이션)
const TRADES_PER_PAGE = 10;

/**
 * 페이지네이션 윈도잉 — 페이지 수가 많아도 항상 최대 7~9개 버튼만 노출.
 * 가운데 생략(`...`)으로 압축하고, 현재 페이지 주변과 첫/마지막은 항상 보여준다.
 */
function getPageWindow(current, total) {
  const MAX_VISIBLE = 7;
  if (total <= MAX_VISIBLE) return Array.from({ length: total }, (_, i) => i + 1);
  if (current <= 4) return [1, 2, 3, 4, 5, '...', total];
  if (current >= total - 3) return [1, '...', total - 4, total - 3, total - 2, total - 1, total];
  return [1, '...', current - 1, current, current + 1, '...', total];
}

/**
 * indicatorsSnapshot — 백엔드가 객체로 내려주는 판단 시점 지표값.
 * 원본 JSON 을 그대로 노출하면 가독성이 0 이므로 key/value 표 형태로 풀어준다.
 * 자주 등장하는 키는 한국어 라벨로 매핑, 모르는 키는 그대로 노출.
 */
const INDICATOR_LABEL = {
  rsi: 'RSI',
  rsi14: 'RSI(14)',
  macd: 'MACD',
  macdSignal: 'MACD Signal',
  bbUpper: '볼린저 상단',
  bbMiddle: '볼린저 중심',
  bbLower: '볼린저 하단',
  sma5: '5일 이동평균',
  sma20: '20일 이동평균',
  sma60: '60일 이동평균',
  sma120: '120일 이동평균',
  ma5: '5일 이동평균',
  ma20: '20일 이동평균',
  ma60: '60일 이동평균',
  volume: '거래량',
  currentPrice: '현재가',
  closePrice: '종가',
  openPrice: '시가',
  highPrice: '고가',
  lowPrice: '저가',
  changeRate: '등락률',
  changeAmount: '변동액',
};

function formatSnapshotValue(value) {
  if (value == null) return '-';
  if (typeof value === 'number') return value.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'object') {
    // 중첩 객체/배열 — 짧게 JSON 으로
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

// 페이지네이션 버튼 전용 유동적 인라인 스타일 (디자인 테마에 완벽 정합)
function paginationBtnStyle(isActive, isDisabled) {
  return {
    padding: '0.4rem 0.8rem',
    borderRadius: '6px',
    border: '1px solid rgba(255, 255, 255, 0.1)',
    backgroundColor: isActive 
      ? 'var(--secondary, #84cc16)' 
      : isDisabled 
        ? 'transparent' 
        : 'rgba(255, 255, 255, 0.05)',
    color: isDisabled 
      ? 'rgba(255, 255, 255, 0.3)' 
      : isActive 
        ? '#0d0d0d' // 연두색 배경 위에서는 검은색 텍스트가 훨씬 가독성 높음 (고품격 UI)
        : 'rgba(255, 255, 255, 0.8)',
    fontWeight: isActive ? '700' : 'normal',
    cursor: isDisabled ? 'not-allowed' : 'pointer',
    fontSize: '0.9rem',
    transition: 'all 0.2s ease',
  };
}

// 매매 시간을 사용자가 가장 읽기 편한 직관적인 포맷(YYYY.MM.DD HH:mm:ss)으로 변환하는 유틸
function formatDecidedAt(dateStr) {
  if (!dateStr) return '';
  try {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hours = String(d.getHours()).padStart(2, '0');
    const minutes = String(d.getMinutes()).padStart(2, '0');
    const seconds = String(d.getSeconds()).padStart(2, '0');
    return `${year}.${month}.${day} ${hours}:${minutes}:${seconds}`;
  } catch {
    return dateStr;
  }
}

export default function ReportPage() {
  const location = useLocation();
  const searchParams = new URLSearchParams(location.search);
  const initialLogId = searchParams.get('logId');

  const [logs, setLogs] = useState([]);
  // 수동 매매 이력 (getOrderHistory). 연결 실패 시 mock 유지.
  const [generalLogs, setGeneralLogs] = useState(MOCK_GENERAL_HISTORY);
  const [expandedLogId, setExpandedLogId] = useState(initialLogId ? parseInt(initialLogId, 10) : null);
  const [detailedDecisions, setDetailedDecisions] = useState({});
  // 매매 이력 섹션 페이지네이션 (다른 섹션과 독립적으로 동작)
  const [tradePage, setTradePage] = useState(1);

  // AI 판단 이력 조회. 실패하면 빈 목록 유지.
  useEffect(() => {
    let cancelled = false;
    async function fetchLogs() {
      try {
        const response = await getAiDecisions({ page: 0, size: 20 });
        if (cancelled) return;
        setLogs(response?.content ?? []);
      } catch (error) {
        if (cancelled) return;
        console.warn('AI 판단 이력 조회 실패:', error);
        setLogs([]);
      }
    }
    fetchLogs();
    return () => {
      cancelled = true;
    };
  }, []);

  // 수동 매매 이력 조회 (getOrderHistory). 연결 실패 시 mock 유지.
  useEffect(() => {
    let cancelled = false;
    async function fetchHistory() {
      try {
        const response = await getOrderHistory({ page: 1, size: 20 });
        if (cancelled) return;
        if (response?.orders?.length) {
          setGeneralLogs(response.orders);
        }
      } catch (error) {
        if (cancelled) return;
        if (error.status !== 404) {
          console.warn('거래 이력 조회 실패 (mock 표시 유지):', error);
        }
      }
    }
    fetchHistory();
    return () => {
      cancelled = true;
    };
  }, []);

  // AI+수동 이력 병합 (시간 내림차순)
  const mergedLogs = useMemo(() => {
    const aiItems = logs.map(log => ({ ...log, source: 'AI' }));
    const manualItems = generalLogs.map(log => ({
      id: `manual-${log.orderId}`,
      source: log.source ?? 'MANUAL',
      stockCode: log.stockCode,
      stockName: log.stockName,
      action: log.side, // 'BUY' | 'SELL'
      price: log.price,
      quantity: log.quantity,
      status: log.status, // 'FILLED' | 'CANCELED' | 'MODIFIED' | 'PENDING' | 'REJECTED'
      decidedAt: log.createdAt,
      confidence: null,
      reason: null,
      orderId: log.orderId ?? null,
    }));
    return [...aiItems, ...manualItems].sort(
      (a, b) => new Date(b.decidedAt) - new Date(a.decidedAt)
    );
  }, [logs, generalLogs]);

  // 매매 이력 페이지네이션: 현재 페이지에 해당하는 슬라이스
  const totalTradePages = Math.max(1, Math.ceil(mergedLogs.length / TRADES_PER_PAGE));
  const paginatedLogs = useMemo(() => {
    const start = (tradePage - 1) * TRADES_PER_PAGE;
    return mergedLogs.slice(start, start + TRADES_PER_PAGE);
  }, [mergedLogs, tradePage]);

  // 데이터 변동으로 totalPages가 줄어 현재 페이지가 범위를 벗어나면 마지막 페이지로 보정
  useEffect(() => {
    if (tradePage > totalTradePages) setTradePage(totalTradePages);
  }, [tradePage, totalTradePages]);

  const goToTradePage = (p) => {
    if (p < 1 || p > totalTradePages) return;
    setTradePage(p);
  };

  // 아코디언 토글 + 펼칠 때 단건 상세(indicatorsSnapshot 포함) 조회
  const toggleLog = async (id) => {
    if (expandedLogId === id) {
      setExpandedLogId(null);
      return;
    }
    setExpandedLogId(id);

    if (detailedDecisions[id]) return;
    const log = logs.find((l) => l.id === id);
    if (!log?.orderId) return;
    try {
      const detail = await getAiDecisionByOrder(log.orderId);
      setDetailedDecisions((prev) => ({ ...prev, [id]: detail }));
    } catch (error) {
      console.warn('상세 판단 근거 조회 실패:', error);
    }
  };

  return (
    <div className="report-container" style={{ padding: '0 0.5rem' }}>
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>리포트</h1>
          <p>내 포트폴리오 분석 결과와 매매 기록을 확인하세요.</p>
        </div>
      </div>

      {/* 3. 매매 이력 통합 */}
      <div className="report-panel">
        <div className="report-panel-header">
          <h2>매매 이력</h2>
        </div>


        <div className="trade-log-list">
          {paginatedLogs.map((log) => {
            const isAI = log.source === 'AI';
            const isExpanded = isAI && expandedLogId === log.id;
            const actionDisplay = ACTION_DISPLAY[log.action] ?? ACTION_DISPLAY.UNKNOWN;
            // AI는 판단 실행 status(READY/HOLD/...) 표시, 수동은 주문 status(체결/정정/취소/...) 표시
            const statusDisplay = isAI
              ? EXECUTION_STATUS_DISPLAY[log.executionStatus]
              : ORDER_STATUS_DISPLAY[log.status];
            const detail = detailedDecisions[log.id];
            return (
              <div key={log.id} className={`trade-log-item ${isExpanded ? 'expanded' : ''}`}>
                <div
                  className={`log-header${isAI ? ' clickable' : ''}`}
                  onClick={isAI ? () => toggleLog(log.id) : undefined}
                >
                  <div className="log-header-left">
                    <span
                      className={`log-badge ${(log.action ?? 'unknown').toLowerCase()}`}
                      style={{ color: actionDisplay.color }}
                    >
                      {actionDisplay.label}
                    </span>
                    <span className="log-stock">{log.stockName || log.stockCode}</span>
                    {log.stockName && (
                      <span style={{ color: '#888', fontSize: '0.85em' }}>{log.stockCode}</span>
                    )}
                    {statusDisplay && (
                      <span className="log-status" style={{ color: statusDisplay.color, fontSize: '0.85em' }}>
                        · {statusDisplay.label}
                      </span>
                    )}
                    {log.confidence != null && (
                      <span className="log-confidence" style={{ fontSize: '0.85em', color: '#aaa' }}>
                        신뢰도 {log.confidence}
                      </span>
                    )}
                  </div>
                  <div className="log-header-right">
                    <span className={`source-badge ${isAI ? 'ai' : 'manual'}`}>
                      {isAI ? 'AI' : '수동'}
                    </span>
                    <span className="log-time">{formatDecidedAt(log.decidedAt)}</span>
                    {isAI && (isExpanded ? <ChevronUp size={20} color="#888" /> : <ChevronDown size={20} color="#888" />)}
                  </div>
                </div>

                {isExpanded && (
                  <div className="log-reason-box">
                    <h4>AI 판단 근거</h4>
                    <p className="reason-text">{log.reason}</p>
                    {detail?.indicatorsSnapshot && (
                      <div className="market-snapshot" style={{ marginTop: '0.75rem' }}>
                        <h4 style={{ marginBottom: '0.5rem' }}>판단 시점 지표 스냅샷</h4>
                        <div className="snapshot-grid" style={{ 
                          display: 'grid', 
                          gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', 
                          gap: '0.5rem', 
                          background: 'rgba(255, 255, 255, 0.03)', 
                          padding: '0.75rem', 
                          borderRadius: '6px',
                          border: '1px solid rgba(255, 255, 255, 0.05)'
                        }}>
                          {Object.entries(detail.indicatorsSnapshot).map(([key, val]) => {
                            const label = INDICATOR_LABEL[key] || key;
                            const formatted = formatSnapshotValue(val);
                            return (
                              <div key={key} className="snapshot-item" style={{ 
                                display: 'flex', 
                                justifyContent: 'space-between', 
                                alignItems: 'center',
                                padding: '0.3rem 0.5rem',
                                background: 'rgba(255, 255, 255, 0.02)',
                                borderRadius: '4px',
                                fontSize: '0.85rem'
                              }}>
                                <span className="snapshot-key" style={{ color: 'rgba(255, 255, 255, 0.5)' }}>{label}</span>
                                <strong className="snapshot-value" style={{ color: '#fff' }}>{formatted}</strong>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* 매매 이력 페이지네이션 — 이 섹션만 페이지 전환, 위쪽 카드는 그대로 유지 */}
        {mergedLogs.length > 0 && totalTradePages > 1 && (
          <div
            className="trade-pagination"
            style={{
              display: 'flex',
              justifyContent: 'center',
              alignItems: 'center',
              gap: '0.4rem',
              marginTop: '1rem',
              flexWrap: 'wrap',
            }}
          >
            <button
              className="page-btn"
              onClick={() => goToTradePage(tradePage - 1)}
              disabled={tradePage === 1}
              style={paginationBtnStyle(false, tradePage === 1)}
            >
              ‹ 이전
            </button>
            {Array.from({ length: totalTradePages }, (_, i) => i + 1).map((p) => (
              <button
                key={p}
                className={`page-btn ${p === tradePage ? 'active' : ''}`}
                onClick={() => goToTradePage(p)}
                style={paginationBtnStyle(p === tradePage, false)}
              >
                {p}
              </button>
            ))}
            <button
              className="page-btn"
              onClick={() => goToTradePage(tradePage + 1)}
              disabled={tradePage === totalTradePages}
              style={paginationBtnStyle(false, tradePage === totalTradePages)}
            >
              다음 ›
            </button>
          </div>
        )}
      </div>

    </div>
  );
}
