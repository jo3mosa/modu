import { useEffect, useRef, useState, useCallback } from 'react';
import { createChart } from 'lightweight-charts';
import { SMA, BollingerBands, RSI, MACD } from 'technicalindicators';
import { ChevronDown } from 'lucide-react';
import { getStockCandles } from '../api/market';
import { getOrderHistory } from '../api/order';
import { buildStockWsUrl } from '../api/wsUrl';
import './TradingChart.css';

// 보조지표 키
const INDICATOR = {
  MA: 'MA',
  BB: 'BB',
  MACD: 'MACD',
  RSI: 'RSI',
  VOLUME: 'VOLUME',
};

// 보조지표별 표시 설정
const MA_PERIODS = [5, 20, 60];
const MA_COLORS = { 5: '#fbbf24', 20: '#06b6d4', 60: '#a78bfa' };
const BB_PERIOD = 20;
const BB_STDDEV = 2;

// SMA 계산 → lightweight-charts 라인 데이터 형식
function calcMA(candles, period) {
  if (candles.length < period) return [];
  const values = SMA.calculate({ period, values: candles.map((c) => c.close) });
  return values.map((v, i) => ({ time: candles[i + period - 1].time, value: v }));
}

// 볼린저밴드 계산 → upper/middle/lower 3개 라인
function calcBB(candles, period = BB_PERIOD, stdDev = BB_STDDEV) {
  if (candles.length < period) return { upper: [], middle: [], lower: [] };
  const result = BollingerBands.calculate({
    period,
    stdDev,
    values: candles.map((c) => c.close),
  });
  const toLine = (key) => result.map((v, i) => ({ time: candles[i + period - 1].time, value: v[key] }));
  return { upper: toLine('upper'), middle: toLine('middle'), lower: toLine('lower') };
}

// RSI 계산
function calcRSI(candles, period = 14) {
  if (candles.length <= period) return [];
  const result = RSI.calculate({ period, values: candles.map((c) => c.close) });
  const offset = candles.length - result.length;
  return result.map((v, i) => ({ time: candles[offset + i].time, value: v }));
}

// MACD 계산
function calcMACD(candles) {
  const slowPeriod = 26;
  const signalPeriod = 9;
  if (candles.length < slowPeriod + signalPeriod) return { macd: [], signal: [], hist: [] };

  const result = MACD.calculate({
    fastPeriod: 12,
    slowPeriod: 26,
    signalPeriod: 9,
    values: candles.map((c) => c.close),
    SimpleMAOscillator: false,
    SimpleMASignal: false,
  });

  const offset = candles.length - result.length;
  return {
    macd: result.map((v, i) => ({ time: candles[offset + i].time, value: v.MACD })),
    signal: result.map((v, i) => ({ time: candles[offset + i].time, value: v.signal })),
    hist: result.map((v, i) => ({
      time: candles[offset + i].time,
      value: v.histogram,
      color: v.histogram >= 0 ? '#ef4444' : '#3b82f6',
    })),
  };
}

// timeframe 옵션은 백엔드 period 값과 일대일 대응 (D/W/M/1/5/60)
// 분봉(1/5/60)은 너무 많아 보여서 드롭다운으로 묶는다. 일/주/월은 사용 빈도가 높아 그대로 노출.
const MINUTE_OPTIONS = [
  { label: '1분', value: '1' },
  { label: '5분', value: '5' },
  { label: '60분', value: '60' },
];

const isMinuteValue = (v) => v === '1' || v === '5' || v === '60';

const TIMEFRAME_OPTIONS = [
  ...MINUTE_OPTIONS,
  { label: '일', value: 'D' },
  { label: '주', value: 'W' },
  { label: '월', value: 'M' },
];

const DAILY_PERIODS = new Set(['D', 'W', 'M']);

// 무한 스크롤 시 한 번에 가져올 추가 범위 (period별)
// 가장 오래된 캔들 기준 이 기간만큼 과거로 추가 fetch한다.
const LOAD_MORE_RANGE = {
  D: { months: 6 },
  W: { years: 2 },
  M: { years: 5 },
  '1': { days: 3 },
  '5': { days: 7 },
  '60': { days: 30 },
};

// timeScale visibleRange.from이 이 임계값 이하로 내려가면 과거 데이터 추가 fetch
const LOAD_MORE_THRESHOLD = 10;

// YYYYMMDD 포맷터
function toBasicIsoDate(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}${m}${d}`;
}

// lightweight-charts에 넘길 수 있는 안전한 캔들인지 검증
// (KIS가 휴장일 등에 OHLC 중 일부가 빈/0으로 응답하는 케이스 방어)
function isValidCandle(c) {
  if (!c) return false;
  if (c.time == null || c.time === '') return false;
  if (!Number.isFinite(c.open)) return false;
  if (!Number.isFinite(c.high)) return false;
  if (!Number.isFinite(c.low)) return false;
  if (!Number.isFinite(c.close)) return false;
  return true;
}

// time이 string('YYYY-MM-DD')일 수도 number(unix sec)일 수도 있으므로 안전한 비교
function compareCandleTime(a, b) {
  const ta = a.time;
  const tb = b.time;
  if (typeof ta === 'number' && typeof tb === 'number') return ta - tb;
  return String(ta).localeCompare(String(tb));
}

// 무한 스크롤용: 가장 오래된 캔들 timestamp → 그 직전 시점의 [startDate, endDate]
function buildOlderDateRange(period, oldestTimestampSec) {
  const oldestDate = new Date(oldestTimestampSec * 1000);
  const endDate = new Date(oldestDate);
  endDate.setDate(endDate.getDate() - 1); // 중복 방지: 가장 오래된 캔들 직전까지

  const startDate = new Date(endDate);
  const range = LOAD_MORE_RANGE[period] ?? { months: 1 };
  if (range.years) startDate.setFullYear(startDate.getFullYear() - range.years);
  if (range.months) startDate.setMonth(startDate.getMonth() - range.months);
  if (range.days) startDate.setDate(startDate.getDate() - range.days);

  return { startDate: toBasicIsoDate(startDate), endDate: toBasicIsoDate(endDate) };
}

/**
 * 백엔드 CandleResponse → lightweight-charts 호환 형식으로 변환
 * - 일/주/월봉: timestamp 'YYYYMMDD' → 'YYYY-MM-DD'
 * - 분봉: timestamp 'HHmmss' → 오늘 날짜 + 해당 시각의 unix seconds
 */
// 주문 createdAt(ISO 8601) → 캔들 time 형식과 동일한 키
// (일/주/월봉은 'YYYY-MM-DD' 문자열, 분봉은 unix seconds)
function buildMarkerTime(period, isoTimestamp) {
  if (DAILY_PERIODS.has(period)) {
    return isoTimestamp.split('T')[0];
  }
  return Math.floor(new Date(isoTimestamp).getTime() / 1000);
}

// 분봉 등에서 주문 체결 시각을 차트의 해당 캔들 시각에 정확히 매칭하기 위한 함수
function findCandleTimeForOrder(period, isoTimestamp, candles = []) {
  if (DAILY_PERIODS.has(period)) {
    return isoTimestamp.split('T')[0];
  }

  const orderTimeSec = Math.floor(new Date(isoTimestamp).getTime() / 1000);
  if (candles.length === 0) return orderTimeSec;

  let matchedTime = null;
  for (let i = 0; i < candles.length; i++) {
    const cTime = typeof candles[i].time === 'number'
      ? candles[i].time
      : Math.floor(new Date(candles[i].time).getTime() / 1000);

    if (cTime <= orderTimeSec) {
      matchedTime = candles[i].time;
    } else {
      break;
    }
  }

  return matchedTime ?? candles[0].time;
}

// 한 마커의 정렬용 epoch(ms) — setMarkers는 time 오름차순이 필수
function markerEpoch(marker) {
  if (marker.createdAt) {
    return new Date(marker.createdAt).getTime();
  }
  return typeof marker.time === 'string'
    ? new Date(marker.time).getTime()
    : marker.time * 1000;
}

// AI 자동매매 주문 → 차트 마커 변환
async function fetchAiMarkers(stockCode, period, candles = []) {
  let aiOrders = [];
  let userOrders = [];

  try {
    const aiRes = await getOrderHistory({ source: 'AUTO', size: 100 });
    aiOrders = aiRes?.orders ?? [];
  } catch (err) {
    console.warn('AI 자동 매매 내역 조회 실패:', err);
  }

  try {
    const userRes = await getOrderHistory({ source: 'MANUAL', size: 100 });
    userOrders = userRes?.orders ?? [];
  } catch (err) {
    console.warn('수동 매매 내역 조회 실패:', err);
  }

  const formattedAi = aiOrders
    .filter((o) => o.stockCode === stockCode && (o.side === 'BUY' || o.side === 'SELL') && o.status === 'FILLED')
    .map((o) => {
      const isBuy = o.side === 'BUY';
      return {
        time: findCandleTimeForOrder(period, o.createdAt, candles),
        position: isBuy ? 'belowBar' : 'aboveBar',
        color: isBuy ? '#ef4444' : '#3b82f6',
        shape: isBuy ? 'arrowUp' : 'arrowDown',
        text: isBuy ? 'AI 매수' : 'AI 매도',
        orderId: o.id,
        isAi: true,
        createdAt: o.createdAt,
      };
    });

  const formattedUser = userOrders
    .filter((o) => o.stockCode === stockCode && (o.side === 'BUY' || o.side === 'SELL') && o.status === 'FILLED')
    .map((o) => {
      const isBuy = o.side === 'BUY';
      return {
        time: findCandleTimeForOrder(period, o.createdAt, candles),
        position: isBuy ? 'belowBar' : 'aboveBar',
        color: isBuy ? '#10b981' : '#f59e0b',
        shape: isBuy ? 'arrowUp' : 'arrowDown',
        text: isBuy ? '내 매수' : '내 매도',
        orderId: o.id,
        isAi: false,
        createdAt: o.createdAt,
      };
    });

  const combined = [...formattedAi, ...formattedUser];
  let sorted = combined.sort((a, b) => markerEpoch(a) - markerEpoch(b));

  if (DAILY_PERIODS.has(period)) {
    // 일/주/월봉에서는 매매가 여러 번 발생했어도 하루(unique time) + 유형(text)당 마커 하나만 표시하여 매수/매도가 서로 가리지 않게 함
    const seenKeys = new Set();
    sorted = sorted.filter((m) => {
      const key = `${m.time}_${m.text}`;
      if (seenKeys.has(key)) return false;
      seenKeys.add(key);
      return true;
    });
  }

  return sorted;
}

function adaptCandle(period, c) {
  const ts = String(c.timestamp ?? '');
  // timestamp 없는 캔들은 무의미 → null로 표시해 호출부에서 filter
  if (!ts) return null;

  // 1. 일/주/월봉 (YYYYMMDD - 8자리)
  if (DAILY_PERIODS.has(period) || ts.length === 8) {
    const time = `${ts.slice(0, 4)}-${ts.slice(4, 6)}-${ts.slice(6, 8)}`;
    return {
      time,
      open: c.openPrice,
      high: c.highPrice,
      low: c.lowPrice,
      close: c.closePrice,
      volume: c.volume,
    };
  }

  // 2. 분봉 처리 (HHmmss 또는 YYYYMMDDHHmmss)
  let datePart, timePart;
  if (ts.length === 14) {
    // YYYYMMDDHHmmss 형식
    datePart = ts.slice(0, 8);
    timePart = ts.slice(8);
  } else if (ts.length === 6) {
    // HHmmss 형식 (오늘 날짜 가정 - 로컬 시간대 기준)
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    datePart = `${y}${m}${d}`;
    timePart = ts;
  } else {
    // 예외 상황 대비 (현재 시각 사용)
    return {
      time: Math.floor(Date.now() / 1000),
      open: c.openPrice,
      high: c.highPrice,
      low: c.lowPrice,
      close: c.closePrice,
      volume: c.volume,
    };
  }

  const year = parseInt(datePart.slice(0, 4), 10);
  const month = parseInt(datePart.slice(4, 6), 10) - 1;
  const day = parseInt(datePart.slice(6, 8), 10);
  const hh = parseInt(timePart.slice(0, 2), 10);
  const mm = parseInt(timePart.slice(2, 4), 10);
  const ss = parseInt(timePart.slice(4, 6), 10);

  const dateObj = new Date(year, month, day, hh, mm, ss);
  return {
    time: Math.floor(dateObj.getTime() / 1000),
    open: c.openPrice,
    high: c.highPrice,
    low: c.lowPrice,
    close: c.closePrice,
    volume: c.volume,
  };
}

export default function TradingChart({ stockCode }) {
  const chartContainerRef = useRef(null);
  const chartRef = useRef(null);
  const candlestickSeriesRef = useRef(null);
  const volumeSeriesRef = useRef(null);
  const rsiSeriesRef = useRef(null);
  const macdSeriesRefs = useRef({ macd: null, signal: null, hist: null });
  // 보조지표 series refs
  const maSeriesRefs = useRef({ 5: null, 20: null, 60: null });
  const bbSeriesRefs = useRef({ upper: null, middle: null, lower: null });
  // 평단가 가로선 레퍼런스
  const priceLineRef = useRef(null);
  // 클릭 이벤트에서 최신 마커 리스트를 참조하기 위한 ref
  const activeMarkersRef = useRef([]);
  // 클릭한 AI/수동 매매 분석 팝업 상태
  const [selectedDecision, setSelectedDecision] = useState(null);
  // 실시간 틱으로 갱신할 마지막 캔들 (high/low 누적용)
  const lastCandleRef = useRef(null);
  // 무한 스크롤용 상태
  const allCandlesRef = useRef([]);        // 누적 캔들 (시간 오름차순, dedup된 상태)
  const loadingMoreRef = useRef(false);    // 과거 데이터 fetch 중 동시호출 방지
  const noMoreDataRef = useRef(false);     // 더 이상 가져올 데이터가 없음
  // 핸들러 내부에서 최신 stockCode/timeframe 참조용 (timeScale 구독은 마운트 1회)
  const stockCodeRef = useRef(stockCode);
  const timeframeRef = useRef('D');
  const [timeframe, setTimeframe] = useState('D');
  const [isChartReady, setIsChartReady] = useState(false);

  // 분봉 드롭다운(1분/5분/60분) — 외부 클릭 시 자동 닫힘
  const [isMinuteMenuOpen, setIsMinuteMenuOpen] = useState(false);
  const minuteMenuRef = useRef(null);

  useEffect(() => {
    if (!isMinuteMenuOpen) return;
    const handleClickOutside = (e) => {
      if (minuteMenuRef.current && !minuteMenuRef.current.contains(e.target)) {
        setIsMinuteMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isMinuteMenuOpen]);

  // 활성화된 보조지표 (기본값: 이동평균선과 거래량을 기본 켜두어 차트가 가득 차고 고급스럽게 보이도록 함)
  const [activeIndicators, setActiveIndicators] = useState(() => {
    try {
      const saved = localStorage.getItem('activeIndicators');
      return saved ? new Set(JSON.parse(saved)) : new Set([INDICATOR.MA, INDICATOR.VOLUME]);
    } catch {
      return new Set([INDICATOR.MA, INDICATOR.VOLUME]);
    }
  });

  // applyIndicators 콜백 안에서 최신 state 참조용 (의존성 폭주 회피)
  const activeIndicatorsRef = useRef(activeIndicators);
  activeIndicatorsRef.current = activeIndicators;

  // 마커 클릭 이벤트 처리기 (HTS급 프리미엄 다크/글래스모픽 상세 보기 제공)
  const handleMarkerClick = useCallback(async (marker) => {
    if (!marker.orderId) return;

    setSelectedDecision({
      loading: true,
      orderId: marker.orderId,
      side: marker.text.includes('매수') ? 'BUY' : 'SELL',
      isAi: marker.isAi,
    });

    if (!marker.isAi) {
      // 수동 매매 주문인 경우
      setSelectedDecision({
        loading: false,
        orderId: marker.orderId,
        side: marker.text.includes('매수') ? 'BUY' : 'SELL',
        isAi: false,
        reason: '사용자가 직접 수행한 수동 매매 주문입니다.',
        confidence: null,
        indicatorsSnapshot: null,
        decidedAt: new Date(typeof marker.time === 'number' ? marker.time * 1000 : marker.time).toISOString(),
      });
      return;
    }

    try {
      // 백엔드 실데이터 조회
      const data = await getAiDecisionByOrder(marker.orderId);
      const decisionData = {
        judgmentReason: data.reason,
        confidenceScore: data.confidence,
        indicatorsSnapshot: data.indicatorsSnapshot,
        judgedAt: data.decidedAt,
      };

      setSelectedDecision({
        loading: false,
        orderId: marker.orderId,
        side: marker.text.includes('매수') ? 'BUY' : 'SELL',
        isAi: true,
        reason: decisionData.judgmentReason,
        confidence: decisionData.confidenceScore,
        indicatorsSnapshot: decisionData.indicatorsSnapshot,
        decidedAt: decisionData.judgedAt,
      });
    } catch (err) {
      console.error('AI 판단 상세 정보 로드 실패:', err);
      setSelectedDecision({
        loading: false,
        orderId: marker.orderId,
        side: marker.text.includes('매수') ? 'BUY' : 'SELL',
        isAi: true,
        error: 'AI 판단 상세 정보를 불러오는 데 실패했습니다.',
      });
    }
  }, []);

  // [평단가 가로선 업데이트] 종목 변경 시 포트폴리오를 로드하여 Toss 스타일 가로 점선 그리기
  useEffect(() => {
    if (!stockCode || !isChartReady || !candlestickSeriesRef.current) return;
    let active = true;

    async function updatePriceLine() {
      // 기존 평단가 가로선 제거
      if (priceLineRef.current && candlestickSeriesRef.current) {
        try {
          candlestickSeriesRef.current.removePriceLine(priceLineRef.current);
        } catch (err) {
          console.warn('이전 평단가 선 제거 실패:', err);
        }
        priceLineRef.current = null;
      }

      try {
        const portfolio = await getPortfolio();
        if (!active) return;
        const holding = (portfolio?.holdings ?? []).find((h) => h.stockCode === stockCode);
        if (holding && holding.quantity > 0) {
          const incomingAvg = holding.avgBuyPrice ?? 0;
          const derivedAvg = Math.round(
            ((holding.currentPrice ?? 0) * holding.quantity - (holding.pnl ?? 0)) / holding.quantity
          );
          const avgBuyPrice = incomingAvg > 0 ? incomingAvg : derivedAvg;

          if (avgBuyPrice > 0) {
            const priceLine = candlestickSeriesRef.current.createPriceLine({
              price: avgBuyPrice,
              color: '#9ca3af', // Grey
              lineWidth: 2,
              lineStyle: 2, // Dashed = 2
              axisLabelVisible: true,
              title: `평단가: ${avgBuyPrice.toLocaleString()}원`,
            });
            priceLineRef.current = priceLine;
          }
        }
      } catch (err) {
        console.warn('평단가 데이터 로드 실패:', err);
      }
    }

    updatePriceLine();

    return () => {
      active = false;
      if (priceLineRef.current && candlestickSeriesRef.current) {
        try {
          candlestickSeriesRef.current.removePriceLine(priceLineRef.current);
        } catch (err) {
          console.warn('평단가 선 제거 실패:', err);
        }
        priceLineRef.current = null;
      }
    };
  }, [stockCode, isChartReady]);

  const toggleIndicator = (key) => {
    setActiveIndicators((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  // 매 렌더에서 최신값 동기화
  stockCodeRef.current = stockCode;
  timeframeRef.current = timeframe;

  // 토글 변경 시 보조지표 즉시 재적용 및 LocalStorage 영구 보관
  useEffect(() => {
    try {
      localStorage.setItem('activeIndicators', JSON.stringify([...activeIndicators]));
    } catch (e) {
      console.error('보조지표 상태 저장 실패:', e);
    }
    applyIndicators();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeIndicators]);

  // 누적 캔들 기준으로 모든 활성 보조지표 데이터를 setData.
  // 비활성 지표는 빈 배열로 set해 화면에서 숨김.
  const applyIndicators = () => {
    const candles = allCandlesRef.current;
    if (candles.length === 0) return;
    const active = activeIndicatorsRef.current;
    const chart = chartRef.current;
    if (!chart) return;

    // ── Phase 5: 영역 동적 분할 계산 ──
    const bottomPanels = [];
    if (active.has(INDICATOR.VOLUME)) bottomPanels.push('volume');
    if (active.has(INDICATOR.RSI)) bottomPanels.push('rsi');
    if (active.has(INDICATOR.MACD)) bottomPanels.push('macd');

    const count = bottomPanels.length;
    let mainBottom = 0;
    if (count === 1) mainBottom = 0.25;
    else if (count === 2) mainBottom = 0.4;
    else if (count === 3) mainBottom = 0.5;

    // 메인 차트 영역 설정
    chart.priceScale('right').applyOptions({
      scaleMargins: { top: 0.05, bottom: mainBottom },
    });

    // 하단 패널별 영역 배분
    bottomPanels.forEach((key, idx) => {
      const panelHeight = mainBottom / count;
      const top = 1 - mainBottom + panelHeight * idx;
      const bottom = 1 - (1 - mainBottom + panelHeight * (idx + 1));

      const scaleId = key === 'volume' ? '' : key;
      chart.priceScale(scaleId).applyOptions({
        scaleMargins: { top: top + 0.02, bottom: bottom },
      });
    });

    // ── 지표 데이터 업데이트 ──
    // 이동평균선
    MA_PERIODS.forEach((period) => {
      const ref = maSeriesRefs.current[period];
      if (!ref) return;
      ref.setData(active.has(INDICATOR.MA) ? calcMA(candles, period) : []);
    });

    // 볼린저밴드
    if (active.has(INDICATOR.BB)) {
      const bb = calcBB(candles);
      bbSeriesRefs.current.upper?.setData(bb.upper);
      bbSeriesRefs.current.middle?.setData(bb.middle);
      bbSeriesRefs.current.lower?.setData(bb.lower);
    } else {
      bbSeriesRefs.current.upper?.setData([]);
      bbSeriesRefs.current.middle?.setData([]);
      bbSeriesRefs.current.lower?.setData([]);
    }

    // RSI
    if (active.has(INDICATOR.RSI)) {
      rsiSeriesRef.current?.setData(calcRSI(candles));
    } else {
      rsiSeriesRef.current?.setData([]);
    }

    // MACD
    if (active.has(INDICATOR.MACD)) {
      const macd = calcMACD(candles);
      macdSeriesRefs.current.macd?.setData(macd.macd);
      macdSeriesRefs.current.signal?.setData(macd.signal);
      macdSeriesRefs.current.hist?.setData(macd.hist);
    } else {
      macdSeriesRefs.current.macd?.setData([]);
      macdSeriesRefs.current.signal?.setData([]);
      macdSeriesRefs.current.hist?.setData([]);
    }

    // 거래량
    if (active.has(INDICATOR.VOLUME)) {
      volumeSeriesRef.current?.setData(
        candles.map((d) => ({
          time: d.time,
          value: d.volume ?? 0,
          color: d.close >= d.open ? 'rgba(239,68,68,0.4)' : 'rgba(59,130,246,0.4)',
        }))
      );
    } else {
      volumeSeriesRef.current?.setData([]);
    }
  };

  // 차트 생성 (마운트 1회) + 무한 스크롤 트리거 구독
  useEffect(() => {
    if (!chartContainerRef.current) return;

    const chart = createChart(chartContainerRef.current, {
      layout: {
        background: { type: 'solid', color: 'transparent' },
        textColor: '#d1d4dc',
      },
      grid: {
        vertLines: { color: 'rgba(255, 255, 255, 0.05)' },
        horzLines: { color: 'rgba(255, 255, 255, 0.05)' },
      },
      crosshair: { mode: 1 },
      rightPriceScale: { borderColor: 'rgba(255, 255, 255, 0.1)' },
      timeScale: {
        borderColor: 'rgba(255, 255, 255, 0.1)',
        timeVisible: true,
        barSpacing: 8,
        rightOffset: 3,
      },
      localization: {
        locale: 'ko-KR',
        // 차트 하단 tickMark 포맷: 일봉 'M월 d일', 분봉 'HH:mm'
        timeFormatter: (time) => {
          if (typeof time === 'number') {
            const d = new Date(time * 1000);
            return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
          }
          // 'YYYY-MM-DD' string
          const [, m, day] = String(time).split('-');
          return `${parseInt(m, 10)}월 ${parseInt(day, 10)}일`;
        },
      },
    });
    chartRef.current = chart;

    candlestickSeriesRef.current = chart.addCandlestickSeries({
      upColor: '#ef4444',
      downColor: '#3b82f6',
      borderVisible: false,
      wickUpColor: '#ef4444',
      wickDownColor: '#3b82f6',
    });

    // 이동평균선 (5/20/60일) — 가격 차트 위 오버레이
    MA_PERIODS.forEach((period) => {
      maSeriesRefs.current[period] = chart.addLineSeries({
        color: MA_COLORS[period],
        lineWidth: 1,
        priceLineVisible: false,
        lastValueVisible: false,
        title: `${period}일`,
      });
    });

    // 볼린저밴드 (20일, ±2σ) — 가격 차트 위 오버레이
    bbSeriesRefs.current.upper = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.7)',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB 상단',
    });
    bbSeriesRefs.current.middle = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.4)',
      lineWidth: 1,
      lineStyle: 2, // dashed
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB 중단',
    });
    bbSeriesRefs.current.lower = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.7)',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB 하단',
    });

    volumeSeriesRef.current = chart.addHistogramSeries({
      color: '#26a69a',
      priceFormat: { type: 'volume' },
      priceScaleId: '', // volume은 관습적으로 ID 없이 기본 축 하단 사용
    });

    // RSI 시리즈 추가
    rsiSeriesRef.current = chart.addLineSeries({
      color: '#a78bfa',
      lineWidth: 2,
      priceScaleId: 'rsi',
      title: 'RSI',
    });
    // RSI 기준선 추가 (30, 70 실선 - 라벨 숨김으로 깔끔하게 처리)
    rsiSeriesRef.current.createPriceLine({ price: 70, color: 'rgba(255, 255, 255, 0.35)', lineWidth: 1, lineStyle: 0, axisLabelVisible: false, title: '' });
    rsiSeriesRef.current.createPriceLine({ price: 30, color: 'rgba(255, 255, 255, 0.35)', lineWidth: 1, lineStyle: 0, axisLabelVisible: false, title: '' });

    // MACD 시리즈 추가
    macdSeriesRefs.current.macd = chart.addLineSeries({ color: '#3b82f6', lineWidth: 1, priceScaleId: 'macd', title: 'MACD' });
    macdSeriesRefs.current.signal = chart.addLineSeries({ color: '#ef4444', lineWidth: 1, priceScaleId: 'macd', title: 'Signal' });
    macdSeriesRefs.current.hist = chart.addHistogramSeries({ priceScaleId: 'macd', title: 'Hist' });

    chart.priceScale('').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });
    chart.priceScale('rsi').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });
    chart.priceScale('macd').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });

    // 무한 스크롤: 보이는 영역이 가장 왼쪽 캔들 근처에 도달하면 과거 데이터 추가 로드
    const onVisibleLogicalRangeChange = (range) => {
      if (!range) return;
      if (loadingMoreRef.current || noMoreDataRef.current) return;
      if (allCandlesRef.current.length === 0) return;
      if (range.from < LOAD_MORE_THRESHOLD) {
        loadMoreCandles();
      }
    };
    chart.timeScale().subscribeVisibleLogicalRangeChange(onVisibleLogicalRangeChange);

    // 차트 클릭 이벤트 리스너: 마커 클릭 감지용 (수동 매매 마커는 클릭 방지)
    const onClick = (param) => {
      if (!param.point || !param.time) return;
      const marker = activeMarkersRef.current.find((m) => m.time === param.time);
      if (marker && marker.isAi) {
        handleMarkerClick(marker);
      }
    };
    chart.subscribeClick(onClick);

    const handleResize = () => {
      chart.applyOptions({
        width: chartContainerRef.current.clientWidth,
        height: chartContainerRef.current.clientHeight,
      });
    };
    window.addEventListener('resize', handleResize);
    setTimeout(handleResize, 100);

    setIsChartReady(true);

    return () => {
      setIsChartReady(false);
      window.removeEventListener('resize', handleResize);
      chart.unsubscribeClick(onClick);
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(onVisibleLogicalRangeChange);
      chart.remove();
      chartRef.current = null;
      candlestickSeriesRef.current = null;
      volumeSeriesRef.current = null;
    };
  }, []);

  // 가장 오래된 캔들 직전 구간을 추가로 가져와 차트 앞쪽에 prepend.
  // 동시호출 방지(loadingMoreRef)와 데이터 소진 감지(noMoreDataRef)는 호출부에서 일부 처리하지만
  // race 안전을 위해 함수 진입 시점에도 다시 확인한다.
  async function loadMoreCandles() {
    if (loadingMoreRef.current || noMoreDataRef.current) return;
    const oldest = allCandlesRef.current[0];
    if (!oldest) return;

    const period = timeframeRef.current;
    const stock = stockCodeRef.current;
    const oldestSec = typeof oldest.time === 'number'
      ? oldest.time
      : Math.floor(new Date(oldest.time).getTime() / 1000);
    const { startDate, endDate } = buildOlderDateRange(period, oldestSec);

    loadingMoreRef.current = true;
    try {
      const response = await getStockCandles(stock, { period, startDate, endDate });
      // 응답 도착 시점 종목/기간이 바뀌었으면 이번 결과는 폐기
      if (stock !== stockCodeRef.current || period !== timeframeRef.current) return;

      const rawCandles = response?.candles ?? [];
      if (rawCandles.length === 0) {
        noMoreDataRef.current = true;
        return;
      }

      const adapted = rawCandles
        .map((c) => adaptCandle(period, c))
        .filter(isValidCandle)
        .sort(compareCandleTime);

      // 기존 + 새 데이터 머지 + 중복 제거
      const seenTimes = new Set(allCandlesRef.current.map((c) => c.time));
      const merged = [...allCandlesRef.current];
      let addedCount = 0;
      for (const c of adapted) {
        if (!seenTimes.has(c.time)) {
          merged.push(c);
          seenTimes.add(c.time);
          addedCount += 1;
        }
      }
      if (addedCount === 0) {
        // 응답은 있었지만 전부 기존과 중복 → 사실상 더 이상 새로운 데이터 없음
        noMoreDataRef.current = true;
        return;
      }
      merged.sort(compareCandleTime);

      // setData 직전 visible range 보존 (prepend 후 스크롤 위치 튐 방지)
      const timeScale = chartRef.current?.timeScale();
      const prevLogicalRange = timeScale?.getVisibleLogicalRange();

      allCandlesRef.current = merged;
      candlestickSeriesRef.current?.setData(merged);
      // 거래량/MA/BB 등 보조지표는 applyIndicators가 일괄 처리
      applyIndicators();

      // 추가된 개수만큼 logical index를 shift 해 사용자가 보던 위치 유지
      if (prevLogicalRange && timeScale) {
        timeScale.setVisibleLogicalRange({
          from: prevLogicalRange.from + addedCount,
          to: prevLogicalRange.to + addedCount,
        });
      }
    } catch (error) {
      console.error('과거 캔들 데이터 로드 실패:', error);
    } finally {
      loadingMoreRef.current = false;
    }
  }

  // 종목/기간 변경 시 캔들 데이터 fetch + 차트 갱신 (무한 스크롤 상태도 초기화)
  useEffect(() => {
    if (!stockCode || !candlestickSeriesRef.current) return;
    let cancelled = false;

    async function fetchCandles() {
      try {
        // 종목/기간 변경 → 누적 상태 리셋
        allCandlesRef.current = [];
        loadingMoreRef.current = false;
        noMoreDataRef.current = false;

        const response = await getStockCandles(stockCode, { period: timeframe });
        if (cancelled) return;
        const period = response?.period ?? timeframe;
        const rawCandles = response?.candles ?? [];

        // 1. 변환 + invalid 캔들 제거 + 정렬 (KIS 휴장일/빈 캔들 방어)
        const adapted = rawCandles
          .map((c) => adaptCandle(period, c))
          .filter(isValidCandle)
          .sort(compareCandleTime);

        // 2. 중복 제거
        const uniqueAdapted = [];
        const seenTimes = new Set();
        for (const c of adapted) {
          if (!seenTimes.has(c.time)) {
            uniqueAdapted.push(c);
            seenTimes.add(c.time);
          }
        }

        allCandlesRef.current = uniqueAdapted;
        candlestickSeriesRef.current?.setData(uniqueAdapted);
        // 거래량/MA/BB 등 보조지표는 applyIndicators가 일괄 처리
        applyIndicators();
        // 초기 로드 / 기간 전환 시 가장 최신 N개만 보이도록 설정
        // (전체 fit은 캔들이 너무 압축되어 분봉이 점처럼 보임 → visible range 명시)
        const visibleCount = Math.min(80, uniqueAdapted.length);
        if (visibleCount > 0) {
          chartRef.current?.timeScale().setVisibleLogicalRange({
            from: uniqueAdapted.length - visibleCount,
            to: uniqueAdapted.length - 1,
          });
        }
        // 실시간 틱이 들어올 때 합쳐 갱신할 기준 캔들 저장
        lastCandleRef.current = uniqueAdapted.length > 0 ? { ...uniqueAdapted[uniqueAdapted.length - 1] } : null;
        // 종목/기간이 바뀌면 이전 마커는 의미가 없으므로 비워둔다.
        candlestickSeriesRef.current?.setMarkers([]);
        activeMarkersRef.current = [];

        // AI 자동매매 주문 마커: 캔들 setData 직후에 그려야 race condition이 없다.
        // 주문 이력 호출이 실패해도 차트 자체는 정상 표시되어야 하므로 별도 try/catch.
        try {
          const markers = await fetchAiMarkers(stockCode, timeframe, uniqueAdapted);
          if (cancelled) return;
          if (stockCode !== stockCodeRef.current || timeframe !== timeframeRef.current) return;
          candlestickSeriesRef.current?.setMarkers(markers);
          activeMarkersRef.current = markers;
        } catch (markerError) {
          // 404(주문 이력 없음)나 백엔드 미준비 상황은 차트 표시를 막지 않고 경고만 남긴다.
          console.warn('AI 매매 마커 로드 실패:', markerError);
        }
      } catch (error) {
        if (cancelled) return;
        console.error('캔들 데이터 로드 실패:', error);
      }
    }
    fetchCandles();

    return () => {
      cancelled = true;
    };
  }, [stockCode, timeframe]);

  // 실시간 체결가 WebSocket: /ws/stocks/{code}/price
  // 메시지(RealtimePriceResponse)의 currentPrice를 마지막 캔들에 누적해 update
  useEffect(() => {
    if (!stockCode || !candlestickSeriesRef.current) return;

    const ws = new WebSocket(buildStockWsUrl(stockCode, 'price'));

    ws.onmessage = (event) => {
      try {
        const tick = JSON.parse(event.data);
        const price = tick?.currentPrice;
        if (price == null || !candlestickSeriesRef.current) return;

        let last = lastCandleRef.current;

        // 초기 데이터가 없는 경우 현재 시각 기준으로 새 캔들 생성 시작
        if (!last) {
          const nowSeconds = Math.floor(Date.now() / 1000);
          const isMinute = !DAILY_PERIODS.has(timeframe);
          // timeframe이 'D', 'W', 'M'인 경우 24시간, 아니면 분 단위로 버킷 계산
          const interval = isMinute ? parseInt(timeframe, 10) * 60 : 24 * 3600;
          const bucketTime = Math.floor(nowSeconds / interval) * interval;

          last = {
            time: bucketTime,
            open: price,
            high: price,
            low: price,
            close: price,
            volume: 0,
          };
        }

        const updated = {
          time: last.time,
          open: last.open,
          high: Math.max(last.high ?? price, price),
          low: Math.min(last.low ?? price, price),
          close: price,
        };

        candlestickSeriesRef.current.update(updated);
        lastCandleRef.current = { ...updated, volume: last.volume };
      } catch (error) {
        console.error('실시간 체결가 메시지 파싱 실패:', error);
      }
    };

    ws.onerror = (event) => {
      console.warn('실시간 체결가 WebSocket 오류:', event);
    };

    return () => {
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    };
  }, [stockCode]);

  return (
    <div className="chart-wrapper">
      <div className="chart-toolbar">
        <div className="toolbar-left">
          {/* 분봉 드롭다운 — 1분/5분/60분을 하나로 묶어 toolbar 공간 절약 */}
          <div className="minute-dropdown" ref={minuteMenuRef}>
            <button
              type="button"
              className={`tool-btn minute-dropdown-trigger ${isMinuteValue(timeframe) ? 'active' : ''}`}
              onClick={() => setIsMinuteMenuOpen((open) => !open)}
            >
              {isMinuteValue(timeframe)
                ? MINUTE_OPTIONS.find((o) => o.value === timeframe)?.label
                : '분봉'}
              <ChevronDown size={12} className="minute-dropdown-caret" />
            </button>
            {isMinuteMenuOpen && (
              <div className="minute-dropdown-menu">
                {MINUTE_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    className={`tool-btn ${timeframe === opt.value ? 'active' : ''}`}
                    onClick={() => {
                      setTimeframe(opt.value);
                      setIsMinuteMenuOpen(false);
                    }}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* 일/주/월 — 사용 빈도 높아 그대로 노출 */}
          {TIMEFRAME_OPTIONS.filter((o) => !isMinuteValue(o.value)).map((opt, idx, arr) => (
            <button
              key={opt.value}
              type="button"
              className={`tool-btn ${timeframe === opt.value ? 'active' : ''}`}
              onClick={() => setTimeframe(opt.value)}
              style={idx === arr.length - 1 ? { marginRight: '1rem' } : {}}
            >
              {opt.label}
            </button>
          ))}

          <span style={{ color: 'rgba(255,255,255,0.2)', marginRight: '1rem' }}>|</span>

          <button
            className={`tool-btn ${activeIndicators.has(INDICATOR.MA) ? 'active' : ''}`}
            onClick={() => toggleIndicator(INDICATOR.MA)}
          >
            이동평균선
          </button>
          <button
            className={`tool-btn ${activeIndicators.has(INDICATOR.BB) ? 'active' : ''}`}
            onClick={() => toggleIndicator(INDICATOR.BB)}
          >
            볼린저밴드
          </button>
          <button
            className={`tool-btn ${activeIndicators.has(INDICATOR.MACD) ? 'active' : ''}`}
            onClick={() => toggleIndicator(INDICATOR.MACD)}
          >
            MACD
          </button>
          <button
            className={`tool-btn ${activeIndicators.has(INDICATOR.RSI) ? 'active' : ''}`}
            onClick={() => toggleIndicator(INDICATOR.RSI)}
          >
            RSI
          </button>
          <button
            className={`tool-btn ${activeIndicators.has(INDICATOR.VOLUME) ? 'active' : ''}`}
            onClick={() => toggleIndicator(INDICATOR.VOLUME)}
          >
            거래량
          </button>
        </div>
      </div>
      <div className="chart-container" ref={chartContainerRef}>
        {selectedDecision && (
          <div className="decision-popup-overlay">
            <div className="popup-header">
              <span className={`popup-badge ${selectedDecision.side === 'BUY' ? 'buy' : 'sell'}`}>
                {selectedDecision.isAi
                  ? (selectedDecision.side === 'BUY' ? 'AI 매수' : 'AI 매도')
                  : (selectedDecision.side === 'BUY' ? '내 매수' : '내 매도')}
              </span>
              <span className="popup-title">AI 판단 요약</span>
              <button className="popup-close-btn" onClick={() => setSelectedDecision(null)}>×</button>
            </div>

            {selectedDecision.loading ? (
              <div className="popup-loading">
                <div className="spinner" />
                <span className="loading-text">AI 분석 데이터 로드 중 ...</span>
              </div>
            ) : selectedDecision.error ? (
              <div className="popup-error">
                <span>{selectedDecision.error}</span>
              </div>
            ) : (
              <div className="popup-body">
                <div className="popup-section">
                  <div className="section-label">판단 근거</div>
                  <div className="section-value reason-text">{selectedDecision.reason}</div>
                </div>

                {selectedDecision.confidence !== null && (
                  <div className="popup-section">
                    <div className="section-label-row">
                      <span>분석 신뢰도</span>
                      <span className="confidence-value">{selectedDecision.confidence}%</span>
                    </div>
                    <div className="confidence-bar-bg">
                      <div
                        className="confidence-bar-fill"
                        style={{ width: `${selectedDecision.confidence}%` }}
                      />
                    </div>
                  </div>
                )}

                {selectedDecision.indicatorsSnapshot && Object.keys(selectedDecision.indicatorsSnapshot).length > 0 && (
                  <div className="popup-section">
                    <div className="section-label">기술 지표 스냅샷</div>
                    <div className="indicator-grid">
                      {Object.entries(selectedDecision.indicatorsSnapshot).map(([key, val]) => (
                        <div className="indicator-card" key={key}>
                          <span className="indicator-key">{key.toUpperCase()}</span>
                          <span className="indicator-val">
                            {typeof val === 'number' ? val.toFixed(2) : String(val)}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div className="popup-footer">
                  <span>분석 일시: {new Date(selectedDecision.decidedAt).toLocaleString()}</span>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}