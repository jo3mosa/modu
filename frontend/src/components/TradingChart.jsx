import { useEffect, useRef, useState } from 'react';
import { createChart } from 'lightweight-charts';
import { SMA, BollingerBands, RSI, MACD } from 'technicalindicators';
import { getStockCandles } from '../api/market';
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
const TIMEFRAME_OPTIONS = [
  { label: '1분', value: '1' },
  { label: '5분', value: '5' },
  { label: '60분', value: '60' },
  { label: '일봉', value: 'D' },
  { label: '주봉', value: 'W' },
  { label: '월봉', value: 'M' },
];

const DAILY_PERIODS = new Set(['D', 'W', 'M']);

// 무한 스크롤 시 한 번에 가져올 추가 범위 (period별)
// 가장 오래된 캔들 기준 이 기간만큼 과거로 추가 fetch한다.
const LOAD_MORE_RANGE = {
  D:   { months: 6 },
  W:   { years: 2 },
  M:   { years: 5 },
  '1':  { days: 3 },
  '5':  { days: 7 },
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

  // 활성화된 보조지표 (기본: 거래량 + 볼린저밴드 + RSI — 기존 hardcode active와 맞춤)
  const [activeIndicators, setActiveIndicators] = useState(
    new Set([INDICATOR.VOLUME, INDICATOR.BB, INDICATOR.MA])
  );
  // applyIndicators 콜백 안에서 최신 state 참조용 (의존성 폭주 회피)
  const activeIndicatorsRef = useRef(activeIndicators);
  activeIndicatorsRef.current = activeIndicators;

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

  // 토글 변경 시 보조지표 즉시 재적용 (차트 series는 살아있고 데이터만 갱신)
  useEffect(() => {
    applyIndicators();
    // applyIndicators는 ref만 읽으므로 의존성 명시 불필요
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

    // 이동평균선 (5/20/60일) — 가격 차트 위 오버레이 (기본 priceScale 공유)
    MA_PERIODS.forEach((period) => {
      maSeriesRefs.current[period] = chart.addLineSeries({
        color: MA_COLORS[period],
        lineWidth: 1,
        priceLineVisible: false,
        lastValueVisible: false,
        title: `MA${period}`,
      });
    });

    // 볼린저밴드 (20일, ±2σ) — 가격 차트 위 오버레이
    bbSeriesRefs.current.upper = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.7)',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Upper',
    });
    bbSeriesRefs.current.middle = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.4)',
      lineWidth: 1,
      lineStyle: 2, // dashed
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Mid',
    });
    bbSeriesRefs.current.lower = chart.addLineSeries({
      color: 'rgba(168, 162, 158, 0.7)',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Lower',
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
    // RSI 기준선 추가 (30, 70)
    rsiSeriesRef.current.createPriceLine({ price: 70, color: 'rgba(255,255,255,0.2)', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '70' });
    rsiSeriesRef.current.createPriceLine({ price: 30, color: 'rgba(255,255,255,0.2)', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '30' });

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

    const handleResize = () => {
      chart.applyOptions({
        width: chartContainerRef.current.clientWidth,
        height: chartContainerRef.current.clientHeight,
      });
    };
    window.addEventListener('resize', handleResize);
    setTimeout(handleResize, 100);

    return () => {
      window.removeEventListener('resize', handleResize);
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

  // ── TODO: AI 매매 내역 마커 시각화 ──
  // useEffect(() => {
  //   if (!stockCode || !candlestickSeriesRef.current) return;
  //   async function fetchAiDecisions() {
  //     try {
  //       const decisions = await getAiDecisions();
  //       const stockDecisions = decisions.filter(
  //         (d) => d.stockCode === stockCode && d.decisionType !== 'HOLD'
  //       );
  //       const markers = stockDecisions.map((d) => {
  //         const timeFormatted = d.decidedAt.split('T')[0];
  //         const isBuy = d.decisionType === 'BUY';
  //         return {
  //           time: timeFormatted,
  //           position: isBuy ? 'belowBar' : 'aboveBar',
  //           color: isBuy ? '#ef4444' : '#3b82f6',
  //           shape: isBuy ? 'arrowUp' : 'arrowDown',
  //           text: `AI ${isBuy ? '매수' : '매도'}`,
  //         };
  //       });
  //       candlestickSeriesRef.current.setMarkers(markers);
  //     } catch (error) {
  //       console.error('AI 판단 데이터 로드 실패:', error);
  //     }
  //   }
  //   fetchAiDecisions();
  // }, [stockCode]);

  // 실시간 체결가 WebSocket: /ws/stocks/{code}/price
  // 메시지(RealtimePriceResponse)의 currentPrice를 마지막 캔들에 누적해 update
  useEffect(() => {
    if (!stockCode || !candlestickSeriesRef.current) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/stocks/${stockCode}/price`);

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
          {TIMEFRAME_OPTIONS.map((opt, idx) => (
            <button
              key={opt.value}
              className={`tool-btn ${timeframe === opt.value ? 'active' : ''}`}
              onClick={() => setTimeframe(opt.value)}
              style={idx === TIMEFRAME_OPTIONS.length - 1 ? { marginRight: '1rem' } : {}}
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
      <div className="chart-container" ref={chartContainerRef} />
    </div>
  );
}