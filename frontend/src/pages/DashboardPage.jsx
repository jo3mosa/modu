import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import Highcharts from 'highcharts';
import HighchartsReactPkg from 'highcharts-react-official';
const HighchartsReact = HighchartsReactPkg.default || HighchartsReactPkg;
import highcharts3d from 'highcharts/highcharts-3d';
import TutorialOverlay from '../components/TutorialOverlay';
import Skeleton from '../components/Skeleton';
import InlineError from '../components/InlineError';
import { getAccountSummary, getPortfolio } from '../api/account';
import { getAiDecisions } from '../api/aiAgent';
import { getOrderHistory, getBuyingPower, ORDER_STATUS_DISPLAY } from '../api/order';
import { getProfile, updateAutoTradeStatus } from '../api/strategy';
import { toast } from 'sonner';
import { useOrderSSE } from '../hooks/useOrderSSE';
import { useNotifications } from '../hooks/useNotifications';
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
  const [isKisConnected, setIsKisConnected] = useState(true);
  const [aiStatus, setAiStatus] = useState(() => {
    const saved = localStorage.getItem('modu.autoTradeActive');
    return { isActive: saved !== 'false' };
  });
  const [profileRiskLevel, setProfileRiskLevel] = useState(null);
  const [aiDecisions, setAiDecisions] = useState([]);
  const [orderHistory, setOrderHistory] = useState([]);
  // 한투 앱 "주문가능원화"와 일치하는 max_buy_amt를 받기 위해 별도 호출
  // (summary.availableCash는 KIS dncl_amt로 미체결 매도 결제 대기분이 빠져있어 사용자 기대값과 어긋남)
  const [buyingPower, setBuyingPower] = useState(null);

  // 영역별 로딩/에러 상태 — 한 영역 실패가 다른 영역 로드를 막지 않도록 분리
  const [summaryState, setSummaryState] = useState({ loading: true, error: null });
  const [holdingsState, setHoldingsState] = useState({ loading: true, error: null });
  const [aiState, setAiState] = useState({ loading: true, error: null });
  const [orderState, setOrderState] = useState({ loading: true, error: null });

  // KIS 미연결 에러 코드 — summary/portfolio 어느 쪽에서 잡아도 페이지 전체 차단 트리거
  const isKisNotConnected = (error) =>
    error?.errorCode === 'KIS_NOT_CONNECTED' || error?.errorCode === 'USER_002';

  // 영역별 fetch — silent=true면 loading 토글 없이 데이터만 갱신 (SSE 등 백그라운드 갱신용)
  const fetchSummary = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setSummaryState((s) => ({ ...s, loading: true }));
    try {
      const data = await getAccountSummary();
      setSummary(data);
      setSummaryState({ loading: false, error: null });
    } catch (error) {
      if (isKisNotConnected(error)) { setIsKisConnected(false); return; }
      setSummaryState({ loading: false, error });
    }
  }, []);

  const fetchPortfolio = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setHoldingsState((s) => ({ ...s, loading: true }));
    try {
      const data = await getPortfolio();
      setHoldings(normalizeHoldings(data.holdings));
      setHoldingsState({ loading: false, error: null });
    } catch (error) {
      if (isKisNotConnected(error)) { setIsKisConnected(false); return; }
      setHoldingsState({ loading: false, error });
    }
  }, []);

  const fetchAi = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setAiState((s) => ({ ...s, loading: true }));
    try {
      const data = await getAiDecisions({ page: 0, size: 10 });
      setAiDecisions(data?.content ?? []);
      setAiState({ loading: false, error: null });
    } catch (error) {
      setAiState({ loading: false, error });
    }
  }, []);

  const fetchOrders = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setOrderState((s) => ({ ...s, loading: true }));
    try {
      const data = await getOrderHistory({ page: 1, size: 10 });
      setOrderHistory(data?.orders ?? []);
      setOrderState({ loading: false, error: null });
    } catch (error) {
      // 404(이력 없음)는 정상 흐름으로 처리 — 빈 배열만 세팅하고 에러 미표시
      if (error.status === 404) {
        setOrderHistory([]);
        setOrderState({ loading: false, error: null });
        return;
      }
      setOrderState({ loading: false, error });
    }
  }, []);

  // 주문 가능 금액 — getBuyingPower 응답의 maxBuyAmount(KIS nrcvb_buy_amt)가 한투 "주문가능원화"와 일치.
  // summary.availableCash(KIS dncl_amt)는 매도 결제 대기 자금이 빠져있어 부정확하므로 보조용으로만 사용.
  // 종목 코드 없이 BUY로 호출하면 계좌 단위 최대 매수 가능 금액을 받음.
  const fetchBuyingPower = useCallback(async () => {
    try {
      const data = await getBuyingPower({ side: 'BUY' });
      setBuyingPower(data ?? null);
    } catch (error) {
      // 404/실패는 조용히 무시 — derivedSummary가 summary.availableCash로 fallback
      if (error?.status !== 404) {
        console.warn('주문가능 조회 실패:', error);
      }
      setBuyingPower(null);
    }
  }, []);

  // 투자 성향은 미설정(404/INVEST_001)이 정상 흐름이라 별도 에러 state 안 둠
  const fetchProfile = useCallback(async () => {
    try {
      const result = await getProfile();
      setProfileRiskLevel(result?.riskLevel ?? null);
    } catch (error) {
      if (error.status !== 404 && error.errorCode !== 'INVEST_001') {
        console.warn('투자 성향 로드 실패:', error);
      }
      setProfileRiskLevel(null);
    }
  }, []);

  useEffect(() => {
    const hasSeenTutorial = localStorage.getItem('hasSeenDashboardTutorial');
    if (!hasSeenTutorial) {
      setShowTutorial(true);
    }
    fetchSummary();
    fetchPortfolio();
    fetchAi();
    fetchOrders();
    fetchProfile();
    fetchBuyingPower();
  }, [fetchSummary, fetchPortfolio, fetchAi, fetchOrders, fetchProfile, fetchBuyingPower]);

  // SSE ORDER_EXECUTED 수신 시 자산/포트폴리오/매매 로그를 즉시 재조회.
  // (자동매매 손절·익절 또는 수동 주문 체결 직후 60초 폴링을 기다리지 않고 화면을 갱신)
  // silent=true로 호출해 loading 깜빡임 없이 데이터만 갱신.
  // 투자 성향(profile)은 변동 없으므로 갱신 대상에서 제외.
  const refreshAccountData = useCallback(() => {
    fetchSummary({ silent: true });
    fetchPortfolio({ silent: true });
    fetchAi({ silent: true });
    fetchOrders({ silent: true });
    fetchBuyingPower();
  }, [fetchSummary, fetchPortfolio, fetchAi, fetchOrders, fetchBuyingPower]);

  const { latestEvent } = useOrderSSE();
  useEffect(() => {
    if (!latestEvent) return;
    if (latestEvent.type === 'ORDER_EXECUTED') {
      // KIS API 서버의 주문 처리가 내부 자산 원장에 완전히 반영되도록 1.5초(1500ms) 딜레이 후 갱신
      // (이렇게 해야 로컬 DB의 미체결 내역과 KIS의 주문가능금액이 완벽히 동기화되어 총자산이 튀는 현상을 막을 수 있습니다!)
      const timer = setTimeout(() => {
        refreshAccountData();
      }, 1500);
      return () => clearTimeout(timer);
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
  // [데이터 출처]
  // - summary.availableCash = KIS dncl_amt (D+2 주문가능현금) — 이미 매수 차감된 정확한 잔액
  // - summary.totalBuyAmount, totalPnl = KIS pchs_amt_smtl, evlu_pfls_amt_smtl (조회 시점 스냅샷)
  // - holdings[].currentPrice는 WebSocket으로 실시간 갱신되므로 평가금액/손익은 holdings 기반 재계산
  //
  // [계산 정책]
  // - totalEvalAmount : Σ(보유수량 × 실시간 현재가)
  // - totalPnl        : Σ(h.pnl) — currentPrice 변동 시 holdings.pnl 도 같이 갱신됨
  // - totalBuyAmount  : summary 값 우선, 없으면 holdings 역산
  // - totalPnlPct     : totalPnl / totalBuyAmount × 100
  // - availableCash   : summary.availableCash 그대로 (KIS dncl_amt) — 재차감 금지
  // - totalAsset      : availableCash + 실시간 totalEvalAmount
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
      status: o.status,       // 'FILLED' | 'CANCELED' | 'MODIFIED' | 'PENDING' | 'REJECTED'
      decidedAt: o.createdAt,
    }));
    return [...aiItems, ...manualItems]
      .filter((l) => l.decidedAt)
      .sort((a, b) => new Date(b.decidedAt) - new Date(a.decidedAt))
      .slice(0, 8);
  }, [aiDecisions, orderHistory]);

  const derivedSummary = useMemo(() => {
    if (!summary) return null;
    // 평가/원금/손익: 모두 holdings 기반으로 통일해 일관성 확보.
    // (KIS 스냅샷과 실시간 holdings 가격이 시점차로 어긋나 totalPnlPct 비율이 어색해지는 것 방지)
    const totalEvalAmount = holdings.reduce(
      (sum, h) => sum + (h.currentPrice ?? 0) * (h.quantity ?? 0),
      0
    );
    const totalBuyAmount = holdings.reduce(
      (sum, h) => sum + (h.avgBuyPrice ?? 0) * (h.quantity ?? 0),
      0
    );
    const totalPnl = holdings.reduce((sum, h) => sum + (h.pnl ?? 0), 0);
    
    // 주문 가능 금액 우선순위: 실제로 신규 매수 주문을 넣을 수 있는 자금 (미체결 매수 주문 시 차감됨)
    const availableCash = buyingPower?.maxBuyAmount ?? summary.availableCash ?? 0;

    // 총 자산 = 주문 가능 금액 + 실시간 주식 평가금액
    // (미체결 매수 보정 로직은 orderHistory PENDING 상태 좀비 데이터로 인해 자산을 과대계상하는 버그가 있어 제거)
    const totalAsset = availableCash + totalEvalAmount;
    
    // 총 자산 대비 실시간 수익률 계산 (투자 직전 총 자산 대비 현재 손익금액의 비율로 구하여 시각적 직관성을 100% 만족시킴!)
    const initialAsset = totalAsset - totalPnl;
    const totalPnlPct = initialAsset > 0 ? Number(((totalPnl / initialAsset) * 100).toFixed(2)) : 0;
    return {
      ...summary,
      availableCash,
      totalEvalAmount,
      totalBuyAmount,
      totalPnl,
      totalPnlPct,
      totalAsset,
    };
  }, [summary, holdings, buyingPower]);

  const handleCloseTutorial = () => {
    localStorage.setItem('hasSeenDashboardTutorial', 'true');
    setShowTutorial(false);
  };

  // 자동매매 ON/OFF 토글 — PATCH /strategies/me/status
  // optimistic update 후 실패 시 롤백. 503은 Kill Switch 발동 케이스로 별도 메시지 + 알림 목록 추가.
  const [togglingAi, setTogglingAi] = useState(false);
  const { addNotification } = useNotifications();
  const toggleAiStatus = async () => {
    if (togglingAi) return;
    const previous = aiStatus.isActive;
    const next = !previous;
    setAiStatus({ isActive: next });
    setTogglingAi(true);
    try {
      const result = await updateAutoTradeStatus({ isActive: next });
      // 백엔드 응답이 진실의 원천 — 실제 반영된 값으로 동기화 및 localStorage 저장
      const finalActive = result.isActive;
      setAiStatus({ isActive: finalActive });
      localStorage.setItem('modu.autoTradeActive', finalActive ? 'true' : 'false');
      toast.success(`자동매매가 ${finalActive ? '활성화' : '비활성화'}되었습니다`);
    } catch (error) {
      setAiStatus({ isActive: previous }); // 롤백
      if (error.status === 503) {
        toast.error('자동매매가 강제 중단됨 (Kill Switch)', {
          description: '안전 한도 초과로 자동매매가 차단된 상태입니다.',
        });
        addNotification({
          type: 'KILL_SWITCH',
          message: '자동매매 강제 중단됨',
          description: '안전 한도 초과 — Kill Switch가 발동되었습니다.',
        });
      } else {
        toast.error(error.message || '자동매매 상태 변경에 실패했습니다.');
      }
    } finally {
      setTogglingAi(false);
    }
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

  // KIS 미연결만 페이지 전체 차단. 그 외엔 페이지 레이아웃 유지하면서
  // 각 영역에서 자체적으로 로딩/에러를 표시한다.
  if (!isKisConnected) return <div className="dashboard-container"><p style={{ padding: '2rem', color: '#ef4444' }}>한국투자증권 API 연동이 필요합니다. 마이페이지에서 설정해주세요.</p></div>;

  // 영역별 표시 분기 — 우선순위: error > loading > 정상
  // 자산 요약: summary + holdings 둘 다 의존(derivedSummary 계산용) → 둘 중 하나라도 실패면 영역 에러
  const summaryError = summaryState.error || holdingsState.error;
  const summaryLoading = (summaryState.loading || holdingsState.loading) || !derivedSummary;
  const holdingsError = holdingsState.error;
  const holdingsLoading = holdingsState.loading && holdings.length === 0;
  // 매매 로그는 AI + 주문 둘 다 의존. 한 쪽만 실패 시 다른 쪽만 조용히 표시, 둘 다 실패 시에만 에러 카드.
  const logsError = aiState.error && orderState.error;
  const logsLoading = (aiState.loading || orderState.loading) && recentLogs.length === 0;

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
            {summaryError ? (
              <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '2rem' }}>
                <InlineError
                  message="자산 정보를 불러오지 못했습니다."
                  onRetry={() => { fetchSummary(); fetchPortfolio(); }}
                />
              </div>
            ) : (<>
            <div className="overview-text">
              <h2>포트폴리오 요약</h2>
              <div className="asset-huge">
                <span className="label">총 자산</span>
                <div className="value-row">
                  {summaryLoading ? (
                    <Skeleton width={180} height={36} />
                  ) : (
                    <>
                      <span className="value">{formatNumber(derivedSummary.totalAsset)}</span>
                      <span className="unit">원</span>
                    </>
                  )}
                </div>
              </div>

              <div className="asset-details">
                <div className="detail-item">
                  <span className="detail-label">총 평가 손익</span>
                  {summaryLoading ? (
                    <Skeleton width={140} height={20} />
                  ) : (
                    <span className={`detail-value ${getColorClass(derivedSummary.totalPnl)}`}>
                      {derivedSummary.totalPnl > 0 ? '+' : ''}{formatNumber(derivedSummary.totalPnl)}원
                      <span className="rate">({derivedSummary.totalPnlPct > 0 ? '+' : ''}{derivedSummary.totalPnlPct}%)</span>
                    </span>
                  )}
                </div>
                <div className="detail-item">
                  <span className="detail-label">투자 원금</span>
                  {summaryLoading ? (
                    <Skeleton width={100} height={18} />
                  ) : (
                    <span className="detail-value">{formatNumber(derivedSummary.totalBuyAmount)}원</span>
                  )}
                </div>
                <div className="detail-item">
                  <span className="detail-label">주문 가능 금액</span>
                  {summaryLoading ? (
                    <Skeleton width={100} height={18} />
                  ) : (
                    <span className="detail-value cash">{formatNumber(derivedSummary.availableCash)}원</span>
                  )}
                </div>
              </div>
            </div>

            <div className="overview-chart">
              {summaryLoading ? (
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: '100%', height: '100%' }}>
                  <Skeleton width={220} height={220} borderRadius="50%" />
                </div>
              ) : (
                <HighchartsReact
                  highcharts={Highcharts}
                  options={chartOptions}
                  containerProps={{ style: { width: '100%', height: '100%' } }}
                />
              )}
            </div>
            </>)}
          </div>

          {/* 보유 종목 리스트 */}
          <div className="panel holdings-panel">
            <h2>보유 종목 상세</h2>
            {holdingsError ? (
              <InlineError
                message="보유 종목을 불러오지 못했습니다."
                onRetry={() => fetchPortfolio()}
              />
            ) : (
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
                  {holdingsLoading ? (
                    // 로딩 중 — 가짜 row 3개로 테이블 형태 유지
                    Array.from({ length: 3 }).map((_, idx) => (
                      <tr key={`sk-${idx}`}>
                        <td className="col-name">
                          <Skeleton width="60%" height={14} />
                          <br />
                          <Skeleton width="40%" height={11} style={{ marginTop: 4 }} />
                        </td>
                        <td><Skeleton width={50} /></td>
                        <td><Skeleton width={70} /></td>
                        <td><Skeleton width={70} /></td>
                        <td><Skeleton width={80} /></td>
                        <td><Skeleton width={50} /></td>
                      </tr>
                    ))
                  ) : (
                    holdings.map((item, idx) => (
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
                    ))
                  )}
                </tbody>
              </table>
            </div>
            )}
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
              {logsError ? (
                <InlineError
                  compact
                  message="거래 이력을 불러오지 못했습니다."
                  onRetry={() => { fetchAi(); fetchOrders(); }}
                />
              ) : logsLoading ? (
                Array.from({ length: 4 }).map((_, idx) => (
                  <div key={`sk-log-${idx}`} className="log-item">
                    <Skeleton width={40} height={40} borderRadius={8} />
                    <div className="log-content" style={{ flex: 1, marginLeft: 8 }}>
                      <div className="log-top">
                        <Skeleton width="50%" height={14} />
                        <Skeleton width={50} height={11} />
                      </div>
                      <div className="log-bottom" style={{ marginTop: 6 }}>
                        <Skeleton width="70%" height={12} />
                      </div>
                    </div>
                  </div>
                ))
              ) : recentLogs.length > 0 ? recentLogs.map((log) => {
                const actionLower = (log.action ?? '').toLowerCase();
                const actionLabel = log.action === 'BUY' ? '매수'
                  : log.action === 'SELL' ? '매도'
                  : log.action === 'HOLD' ? '관망' : '판단';
                const date = new Date(log.decidedAt);
                const timeLabel = `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
                // 수동 매매 주문 상태 라벨 (체결/정정/취소/대기/거절). FILLED는 기본값이라 굳이 표시하지 않는다.
                const statusDisplay = log.source !== 'AI' && log.status && log.status !== 'FILLED'
                  ? ORDER_STATUS_DISPLAY[log.status]
                  : null;
                return (
                  <div key={log.id} className="log-item">
                    <div className={`log-icon ${actionLower}`}>{actionLabel}</div>
                    <div className="log-content">
                      <div className="log-top">
                        <span className="log-stock">
                          {log.stockName ?? log.stockCode}
                          {statusDisplay && (
                            <span style={{ marginLeft: 6, fontSize: '0.85em', color: statusDisplay.color }}>
                              · {statusDisplay.label}
                            </span>
                          )}
                        </span>
                        <span className="log-time">{timeLabel}</span>
                      </div>
                      <div className="log-bottom">
                        {log.orderType === 'MARKET' && log.quantity != null
                          ? `시장가 · ${log.quantity}주`
                          : log.price != null && log.price > 0 && log.quantity != null
                          ? `${log.price.toLocaleString()}원 · ${log.quantity}주`
                          : log.quantity != null
                          ? `${log.quantity}주`
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