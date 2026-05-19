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



// 매매 이력 섹션 페이지당 표시 개수 (클라이언트 페이지네이션)
const TRADES_PER_PAGE = 10;

// 페이지 버튼 스타일 (active/disabled에 따라 다른 색상)
function paginationBtnStyle(isActive, isDisabled) {
  return {
    minWidth: '2rem',
    padding: '0.3rem 0.6rem',
    fontSize: '0.85rem',
    fontWeight: 600,
    border: '1px solid rgba(255,255,255,0.1)',
    borderRadius: '6px',
    background: isActive ? 'rgba(132,204,22,0.2)' : 'transparent',
    color: isActive ? '#84cc16' : isDisabled ? '#444' : '#aaa',
    cursor: isDisabled ? 'not-allowed' : 'pointer',
    transition: 'all 0.15s',
  };
}

// 백엔드 getOrderHistory 응답 표준 형식과 동일 (side / orderType / createdAt / source / status).
const MOCK_GENERAL_HISTORY = [
  { orderId: '1001', stockCode: '005930', stockName: '삼성전자', side: 'BUY', orderType: 'LIMIT', price: 74500, quantity: 10, status: 'FILLED', source: 'MANUAL', createdAt: '2026-05-03 10:30' },
  { orderId: '1002', stockCode: '035420', stockName: 'NAVER', side: 'BUY', orderType: 'LIMIT', price: 184000, quantity: 5, status: 'CANCELED', source: 'MANUAL', createdAt: '2026-05-03 09:50' },
  { orderId: '1003', stockCode: '000660', stockName: 'SK하이닉스', side: 'SELL', orderType: 'LIMIT', price: 149000, quantity: 5, status: 'FILLED', source: 'MANUAL', createdAt: '2026-05-02 09:15' },
  { orderId: '1004', stockCode: '035720', stockName: '카카오', side: 'SELL', orderType: 'LIMIT', price: 45000, quantity: 20, status: 'FILLED', source: 'MANUAL', createdAt: '2026-05-01 14:20' }
];

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
                    <span className="log-time">{log.decidedAt}</span>
                    {isAI && (isExpanded ? <ChevronUp size={20} color="#888" /> : <ChevronDown size={20} color="#888" />)}
                  </div>
                </div>

                {isExpanded && (
                  <div className="log-reason-box">
                    <h4>AI 판단 근거</h4>
                    <p className="reason-text">{log.reason}</p>
                    {detail?.indicatorsSnapshot && (
                      <div className="market-snapshot">
                        <h4 style={{ marginTop: '0.75rem' }}>판단 시점 지표 스냅샷</h4>
                        <pre className="snapshot-json" style={{ background: 'rgba(255,255,255,0.04)', padding: '0.75rem', borderRadius: '6px', fontSize: '0.85em', overflowX: 'auto' }}>
                          {JSON.stringify(detail.indicatorsSnapshot, null, 2)}
                        </pre>
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
