import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import Highcharts from 'highcharts';
import HighchartsReactPkg from 'highcharts-react-official';
const HighchartsReact = HighchartsReactPkg.default || HighchartsReactPkg;
import highcharts3d from 'highcharts/highcharts-3d';
import TutorialOverlay from '../components/TutorialOverlay';
import { getAccountSummary, getPortfolio } from '../api/account';
import { getAiDecisions } from '../api/aiAgent';
import { getOrderHistory } from '../api/order';
import { getProfile } from '../api/strategy';
import { useOrderSSE } from '../hooks/useOrderSSE';
import './DashboardPage.css';

if (typeof Highcharts === 'object') {
  if (typeof highcharts3d === 'function') {
    highcharts3d(Highcharts);
  } else if (highcharts3d && typeof highcharts3d.default === 'function') {
    highcharts3d.default(Highcharts);
  }
}


// ── MOCK 데이터 ──────────────────────────────────
// 필드명은 백엔드 응답 스펙 기준으로 맞춰둠 → 연동 시 그대로 사용 가능
const MOCK_SUMMARY = {
  totalAsset: 600000,       // GET /api/v1/accounts/me/summary
  totalBuyAmount: 570000,
  totalEvalAmount: 400000,
  totalPnl: 30000,
  totalPnlPct: 5.26,
  availableCash: 200000,
};

const MOCK_HOLDINGS = [
  { stockName: '삼성전자', stockCode: '005930', quantity: 3, avgBuyPrice: 70000, currentPrice: 74900, pnl: 14700, pnlPct: 7.00 },
  { stockName: '한화에어로스페이스', stockCode: '012450', quantity: 1, avgBuyPrice: 60000, currentPrice: 85300, pnl: 25300, pnlPct: 42.16 },
  { stockName: '카카오', stockCode: '035720', quantity: 2, avgBuyPrice: 50000, currentPrice: 45000, pnl: -10000, pnlPct: -10.00 },
];

const MOCK_AI_STATUS = {
  isActive: true,
};

// 투자 성향 등급 → "전략" + "위험 수준" 표시값 매핑
// (RiskManagePage의 RISK_LEVEL_LABEL과 동일한 키 사용)
const RISK_LEVEL_DISPLAY = {
  STABLE:         { strategy: '원금 보존형', risk: '매우 낮음' },
  STABLE_SEEKING: { strategy: '안정 수익형', risk: '낮음' },
  RISK_NEUTRAL:   { strategy: '균형 투자형', risk: '보통' },
  ACTIVE:         { strategy: '적극 매매형', risk: '다소 높음' },
  AGGRESSIVE:     { strategy: '공격 매매형', risk: '높음' },
};

const CHART_COLORS = ['#3b82f6', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981', '#84cc16'];

// 백엔드가 avgBuyPrice를 0으로 내려주는 케이스 보정:
//   avgBuyPrice = (currentPrice × quantity − pnl) / quantity
// (KIS는 종목별 pnl 값을 정상적으로 내려주므로 역산 가능)
function normalizeHoldings(holdings) {
  return (holdings ?? []).map((h) => {
    const quantity = h.quantity ?? 0;
    const currentPrice = h.currentPrice ?? 0;
    const pnl = h.pnl ?? 0;
    const incomingAvg = h.avgBuyPrice ?? 0;
    const derivedAvg =
      quantity > 0 ? Math.round((currentPrice * quantity - pnl) / quantity) : 0;
    const avgBuyPrice = incomingAvg > 0 ? incomingAvg : derivedAvg;
    return { ...h, avgBuyPrice };
  });
}

export default function DashboardPage() {
  const navigate = useNavigate();
  const [showTutorial, setShowTutorial] = useState(false);

  const [summary, setSummary] = useState(null);
  const [holdings, setHoldings] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isKisConnected, setIsKisConnected] = useState(true);
  const [aiStatus, setAiStatus] = useState(MOCK_AI_STATUS);
  const [profileRiskLevel, setProfileRiskLevel] = useState(null);
  const [aiDecisions, setAiDecisions] = useState([]);
  const [orderHistory, setOrderHistory] = useState([]);

  useEffect(() => {
    const hasSeenTutorial = localStorage.getItem('hasSeenDashboardTutorial');
    if (!hasSeenTutorial) {
      setShowTutorial(true);
    }

    async function fetchDashboardData() {
      setIsLoading(true);
      try {
        const [summaryData, portfolioData, decisionsData, historyResult, profileResult] = await Promise.all([
          getAccountSummary(),
          getPortfolio(),
          // 최근 매매 로그용 — AI 10개 + 수동 10개 가져와서 합치고 최신 8개 표시
          getAiDecisions({ page: 0, size: 10 }),
          getOrderHistory({ page: 1, size: 10 }).catch((error) => {
            if (error.status !== 404) {
              console.warn('거래 이력 로드 실패:', error);
            }
            return { orders: [] };
          }),
          // 투자 성향 미설정(404) 케이스를 정상 흐름으로 흡수해 다른 데이터 로드를 막지 않음
          getProfile().catch((error) => {
            if (error.status !== 404 && error.errorCode !== 'INVEST_001') {
              console.warn('투자 성향 로드 실패:', error);
            }
            return null;
          }),
        ]);

        setProfileRiskLevel(profileResult?.riskLevel ?? null);
        setSummary(summaryData);
        setHoldings(normalizeHoldings(portfolioData.holdings));
        setAiDecisions(decisionsData?.content ?? []);
        setOrderHistory(historyResult?.orders ?? []);
      } catch (error) {
        if (error.errorCode === 'KIS_NOT_CONNECTED' || error.errorCode === 'USER_002') {
          setIsKisConnected(false);
        } else {
          console.error('대시보드 데이터 로드 실패:', error);
        }
      } finally {
        setIsLoading(false);
      }
    }
    fetchDashboardData();
  }, []);

  // SSE ORDER_EXECUTED 수신 시 자산/포트폴리오/매매 로그를 즉시 재조회.
  // (자동매매 손절·익절 또는 수동 주문 체결 직후 60초 폴링을 기다리지 않고 화면을 갱신)
  // 투자 성향(profile)은 변동 없으므로 갱신 대상에서 제외.
  const refreshAccountData = useCallback(async () => {
    try {
      const [summaryData, portfolioData, decisionsData, historyResult] = await Promise.all([
        getAccountSummary(),
        getPortfolio(),
        getAiDecisions({ page: 0, size: 10 }),
        getOrderHistory({ page: 1, size: 10 }).catch(() => ({ orders: [] })),
      ]);
      setSummary(summaryData);
      setHoldings(normalizeHoldings(portfolioData.holdings));
      setAiDecisions(decisionsData?.content ?? []);
      setOrderHistory(historyResult?.orders ?? []);
    } catch (error) {
      console.warn('체결 후 대시보드 갱신 실패:', error);
    }
  }, []);

  const { latestEvent } = useOrderSSE();
  useEffect(() => {
    if (!latestEvent) return;
    if (latestEvent.type === 'ORDER_EXECUTED') {
      refreshAccountData();
    }
  }, [latestEvent, refreshAccountData]);

  // 보유 종목 코드 리스트 — 가격 변경 시에도 동일 참조를 유지해 WS 재구독을 방지한다.
  const stockCodesKey = useMemo(
    () => holdings.map((h) => h.stockCode).join(','),
    [holdings]
  );

  // 보유 종목별 실시간 체결가 구독: 종목 코드 리스트가 바뀔 때만 재연결
  // 메시지 수신 시 해당 종목의 currentPrice/pnl/pnlPct를 즉시 갱신한다.
  useEffect(() => {
    if (!stockCodesKey) return;
    const codes = stockCodesKey.split(',').filter(Boolean);
    if (codes.length === 0) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const sockets = codes.map((code) => {
      const ws = new WebSocket(`${protocol}//${window.location.host}/ws/stocks/${code}/price`);
      ws.onmessage = (event) => {
        try {
          const tick = JSON.parse(event.data);
          const price = tick?.currentPrice;
          if (price == null) return;
          setHoldings((prev) =>
            prev.map((item) => {
              if (item.stockCode !== code) return item;
              const newPnl = (price - item.avgBuyPrice) * item.quantity;
              const newPnlPct =
                item.avgBuyPrice > 0
                  ? Number((((price - item.avgBuyPrice) / item.avgBuyPrice) * 100).toFixed(2))
                  : 0;
              return { ...item, currentPrice: price, pnl: newPnl, pnlPct: newPnlPct };
            })
          );
        } catch (error) {
          console.error('실시간 체결가 메시지 파싱 실패:', error);
        }
      };
      ws.onerror = (event) => {
        console.warn(`실시간 체결가 WebSocket 오류 (${code}):`, event);
      };
      return ws;
    });

    return () => {
      sockets.forEach((ws) => {
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
          ws.close();
        }
      });
    };
  }, [stockCodesKey]);

  // 예수금/매입금액 보정용 폴링 (KIS 연동 시에만, 60초 주기)
  // 실시간 가격 외 값들(availableCash 등)은 WS가 없으므로 주기적으로 갱신한다.
  // (KIS 초당 거래건수 한도 고려해 60초로 설정 — 너무 짧으면 다른 호출과 겹쳐 EGW00201 발생)
  useEffect(() => {
    if (!isKisConnected) return;
    const intervalId = setInterval(async () => {
      try {
        const data = await getAccountSummary();
        setSummary(data);
      } catch (error) {
        console.warn('자산 요약 폴링 실패:', error);
      }
    }, 60000);
    return () => clearInterval(intervalId);
  }, [isKisConnected]);

  // holdings 기준 파생 요약값
  //
  // [배경] 백엔드 summary.availableCash는 KIS `dncl_amt` (D+0 예수금)을 그대로 매핑한다.
  // 이 값은 종목 매수 후에도 차감되지 않아 사용자가 인식하는 "주문 가능 금액"과 다르다.
  // → 프론트에서 입금 원금으로 간주하고, 투자 원금을 빼서 실제 주문 가능 금액을 산출한다.
  //
  // - principal       : summary.availableCash  (입금 원금 = KIS dncl_amt)
  // - totalEvalAmount : Σ(보유수량 × 현재가)     (보유 종목 현재가치)
  // - totalBuyAmount  : Σ(보유수량 × 매수평균가)  (투자 원금, 역산 보정값)
  // - totalPnl        : Σ(pnl)                 (평가 손익)
  // - totalPnlPct     : totalPnl / totalBuyAmount × 100
  // - availableCash   : principal − totalBuyAmount  (실제 주문 가능 금액)
  // - totalAsset      : availableCash + totalEvalAmount  (= principal + totalPnl 와 등가)
  // 최근 매매 로그: AI 판단 + 수동 주문을 표준 형식으로 통합 후 시간 내림차순 상위 5개
  const recentLogs = useMemo(() => {
    const aiItems = aiDecisions.map((d) => ({
      id: `ai-${d.id}`,
      source: 'AI',
      action: d.action,
      stockCode: d.stockCode,
      stockName: null,
      price: null,
      quantity: null,
      decidedAt: d.decidedAt,
    }));
    const manualItems = orderHistory.map((o) => ({
      id: `manual-${o.orderId}`,
      source: 'MANUAL',
      action: o.side,
      stockCode: o.stockCode,
      stockName: o.stockName,
      price: o.price,
      quantity: o.quantity,
      orderType: o.orderType, // 'LIMIT' | 'MARKET' — 시장가는 가격 표시 대신 라벨로 대체
      decidedAt: o.createdAt,
    }));
    return [...aiItems, ...manualItems]
      .filter((l) => l.decidedAt)
      .sort((a, b) => new Date(b.decidedAt) - new Date(a.decidedAt))
      .slice(0, 8);
  }, [aiDecisions, orderHistory]);

  const derivedSummary = useMemo(() => {
    if (!summary) return null;
    const totalEvalAmount = holdings.reduce(
      (sum, h) => sum + (h.currentPrice ?? 0) * (h.quantity ?? 0),
      0
    );
    const totalBuyAmount = holdings.reduce(
      (sum, h) => sum + (h.avgBuyPrice ?? 0) * (h.quantity ?? 0),
      0
    );
    const totalPnl = holdings.reduce((sum, h) => sum + (h.pnl ?? 0), 0);
    const totalPnlPct =
      totalBuyAmount > 0 ? Number(((totalPnl / totalBuyAmount) * 100).toFixed(2)) : 0;
    const principal = summary.availableCash ?? 0;
    const availableCash = Math.max(0, principal - totalBuyAmount);
    const totalAsset = availableCash + totalEvalAmount;
    return {
      ...summary,
      availableCash,
      totalEvalAmount,
      totalBuyAmount,
      totalPnl,
      totalPnlPct,
      totalAsset,
    };
  }, [summary, holdings]);

  const handleCloseTutorial = () => {
    localStorage.setItem('hasSeenDashboardTutorial', 'true');
    setShowTutorial(false);
  };

  const toggleAiStatus = () => {
    setAiStatus(prev => ({ ...prev, isActive: !prev.isActive }));
  };

  const formatNumber = (num) => num.toLocaleString();
  const getColorClass = (val) => {
    if (val > 0) return 'text-red';
    if (val < 0) return 'text-blue';
    return '';
  };

  // 도넛 차트 데이터 포맷팅 (실시간 currentPrice 기반)
  const chartData = holdings.map((h, i) => ({
    name: h.stockName,
    y: (h.quantity ?? 0) * (h.currentPrice ?? 0),
    color: CHART_COLORS[i % CHART_COLORS.length],
    quantity: h.quantity
  }));
  chartData.push({ name: '주문 가능 금액', y: derivedSummary?.availableCash ?? 0, color: '#84cc16', quantity: null });

  // 비중 내림차순 정렬
  chartData.sort((a, b) => b.y - a.y);

  // Highcharts 3D 옵션
  const chartOptions = {
    chart: {
      type: 'pie',
      options3d: {
        enabled: true,
        alpha: 30,
        beta: 0
      },
      backgroundColor: 'transparent',
      style: { fontFamily: "'Pretendard', sans-serif" },
      margin: [0, 0, 0, 0]
    },
    title: { text: null },
    credits: { enabled: false },
    tooltip: {
      useHTML: true,
      backgroundColor: 'transparent',
      borderColor: 'transparent',
      borderWidth: 0,
      shadow: false,
      padding: 0,
      style: { pointerEvents: 'none', zIndex: 1000 },
      formatter: function () {
        const data = this.point;
        const quantityHtml = data.quantity !== null
          ? `<span style="font-weight:600;">${data.quantity.toLocaleString()}주</span> · `
          : '';
        const valueHtml = `${data.y.toLocaleString()}원`;

        return `
          <div style="background: rgba(0,0,0,0.85); border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 4px 12px rgba(0,0,0,0.3); padding: 10px 15px; border-radius: 8px;">
            <div style="color:${data.color}; font-weight:700; margin-bottom:6px; font-size:1.05rem;">
              ${data.name}
            </div>
            <div style="color:#fff; font-size:0.95rem; font-weight:600;">
              ${quantityHtml}${valueHtml}
            </div>
          </div>
        `;
      }
    },
    plotOptions: {
      pie: {
        innerSize: 130,
        depth: 45,
        size: '80%',
        center: ['50%', '35%'],
        dataLabels: { enabled: false },
        borderWidth: 0,
        showInLegend: false,
        states: {
          hover: { halo: { size: 0 }, brightness: 0.1 }
        }
      }
    },
    series: [{
      name: '자산 비중',
      data: chartData
    }]
  };

  // ── 연동 시 아래 주석 해제 (KIS 미연동/로딩 상태 처리) ──────────────────
  if (isLoading) return <div className="dashboard-container"><p style={{ padding: '2rem', color: '#aaa' }}>자산 정보를 불러오는 중...</p></div>;
  if (!isKisConnected) return <div className="dashboard-container"><p style={{ padding: '2rem', color: '#ef4444' }}>한국투자증권 API 연동이 필요합니다. 마이페이지에서 설정해주세요.</p></div>;
  if (!derivedSummary) return null;
  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div className="dashboard-container">
      {showTutorial && <TutorialOverlay onClose={handleCloseTutorial} />}

      <div className="page-header-container">
        <div className="page-title-group">
          <h1>대시보드</h1>
          <p>전체 자산 현황과 AI 매매 상태를 실시간으로 모니터링하세요.</p>
        </div>
      </div>

      <div className="dashboard-layout">
        {/* 포트폴리오 메인 */}
        <div className="dashboard-main">
          {/* 자산 요약 + 차트 */}
          <div className="panel overview-panel">
            <div className="overview-text">
              <h2>포트폴리오 요약</h2>
              <div className="asset-huge">
                <span className="label">총 자산</span>
                <div className="value-row">
                  <span className="value">{formatNumber(derivedSummary.totalAsset)}</span>
                  <span className="unit">원</span>
                </div>
              </div>

              <div className="asset-details">
                <div className="detail-item">
                  <span className="detail-label">총 평가 손익</span>
                  <span className={`detail-value ${getColorClass(derivedSummary.totalPnl)}`}>
                    {derivedSummary.totalPnl > 0 ? '+' : ''}{formatNumber(derivedSummary.totalPnl)}원
                    <span className="rate">({derivedSummary.totalPnlPct > 0 ? '+' : ''}{derivedSummary.totalPnlPct}%)</span>
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">투자 원금</span>
                  <span className="detail-value">{formatNumber(derivedSummary.totalBuyAmount)}원</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">주문 가능 금액</span>
                  <span className="detail-value cash">{formatNumber(derivedSummary.availableCash)}원</span>
                </div>
              </div>
            </div>

            <div className="overview-chart">
              <HighchartsReact
                highcharts={Highcharts}
                options={chartOptions}
                containerProps={{ style: { width: '100%', height: '100%' } }}
              />
            </div>
          </div>

          {/* 보유 종목 리스트 */}
          <div className="panel holdings-panel">
            <h2>보유 종목 상세</h2>
            <div className="table-wrapper">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>종목명</th>
                    <th>보유 수량</th>
                    <th>매수 평균가</th>
                    <th>현재가</th>
                    <th>평가 손익</th>
                    <th>수익률</th>
                  </tr>
                </thead>
                <tbody>
                  {holdings.map((item, idx) => (
                    <tr
                      key={idx}
                      onClick={() => navigate(`/trading?stock=${item.stockCode}&name=${encodeURIComponent(item.stockName)}`)}
                      className="clickable-row"
                    >
                      <td className="col-name">
                        <span className="stock-name">{item.stockName}</span>
                        <span className="stock-code">{item.stockCode}</span>
                      </td>
                      <td>{formatNumber(item.quantity)}주</td>
                      <td>{formatNumber(item.avgBuyPrice)}원</td>
                      <td>{formatNumber(item.currentPrice)}원</td>
                      <td className={getColorClass(item.pnl)}>
                        {item.pnl > 0 ? '+' : ''}{formatNumber(item.pnl)}원
                      </td>
                      <td className={getColorClass(item.pnlPct)}>
                        {item.pnlPct > 0 ? '+' : ''}{item.pnlPct}%
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* 우측 -> AI on/off */}
        <div className="dashboard-side">
          {/* AI 미니 컨트롤 */}
          <div className="panel ai-mini-panel">
            <h2>AI 자동매매</h2>
            <div className="ai-mini-content">
              <div className="ai-status-row">
                <div className="ai-status-text">
                  <span className={`status-badge ${aiStatus.isActive ? 'active' : 'inactive'}`}>
                    {aiStatus.isActive ? 'ON' : 'OFF'}
                  </span>
                </div>
                {/* 토글 스위치 */}
                <div
                  className={`toggle-switch ${aiStatus.isActive ? 'on' : 'off'}`}
                  onClick={toggleAiStatus}
                >
                  <div className="toggle-knob"></div>
                </div>
              </div>

              <div className="ai-info-box">
                <div className="info-row">
                  <span className="info-label">전략</span>
                  <span className="info-value">
                    {profileRiskLevel
                      ? RISK_LEVEL_DISPLAY[profileRiskLevel]?.strategy ?? '미설정'
                      : '성향 진단 필요'}
                  </span>
                </div>
                <div className="info-row">
                  <span className="info-label">위험 수준</span>
                  <span className="info-value">
                    {profileRiskLevel
                      ? RISK_LEVEL_DISPLAY[profileRiskLevel]?.risk ?? '-'
                      : '-'}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* 최근 매매 로그 (AI + 수동 통합, 최근 5개) */}
          <div className="panel logs-panel">
            <h2>최근 매매 로그</h2>
            <div className="logs-list">
              {recentLogs.length > 0 ? recentLogs.map((log) => {
                const actionLower = (log.action ?? '').toLowerCase();
                const actionLabel = log.action === 'BUY' ? '매수'
                  : log.action === 'SELL' ? '매도'
                  : log.action === 'HOLD' ? '관망' : '판단';
                const date = new Date(log.decidedAt);
                const timeLabel = `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
                return (
                  <div key={log.id} className="log-item">
                    <div className={`log-icon ${actionLower}`}>{actionLabel}</div>
                    <div className="log-content">
                      <div className="log-top">
                        <span className="log-stock">{log.stockName ?? log.stockCode}</span>
                        <span className="log-time">{timeLabel}</span>
                      </div>
                      <div className="log-bottom">
                        {log.orderType === 'MARKET' && log.quantity != null
                          ? `시장가 · ${log.quantity}주`
                          : log.price != null && log.quantity != null
                          ? `${log.price.toLocaleString()}원 · ${log.quantity}주`
                          : (log.source === 'AI' ? 'AI 판단' : '-')}
                      </div>
                    </div>
                  </div>
                );
              }) : (
                <div className="empty-logs">표시할 활동이 없습니다.</div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}