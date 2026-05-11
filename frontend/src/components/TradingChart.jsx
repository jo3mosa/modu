import { useEffect, useRef, useState } from 'react';
import { createChart } from 'lightweight-charts';
import { getStockCandles } from '../api/market';
import './TradingChart.css';

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

/**
 * 백엔드 CandleResponse → lightweight-charts 호환 형식으로 변환
 * - 일/주/월봉: timestamp 'YYYYMMDD' → 'YYYY-MM-DD'
 * - 분봉: timestamp 'HHmmss' → 오늘 날짜 + 해당 시각의 unix seconds
 */
function adaptCandle(period, c) {
  const ts = String(c.timestamp ?? '');
  
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
  const candlestickSeriesRef = useRef(null);
  const volumeSeriesRef = useRef(null);
  // 실시간 틱으로 갱신할 마지막 캔들 (high/low 누적용)
  const lastCandleRef = useRef(null);
  const [timeframe, setTimeframe] = useState('D');

  // 차트 생성 (마운트 1회)
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
      },
    });

    candlestickSeriesRef.current = chart.addCandlestickSeries({
      upColor: '#ef4444',
      downColor: '#3b82f6',
      borderVisible: false,
      wickUpColor: '#ef4444',
      wickDownColor: '#3b82f6',
    });

    volumeSeriesRef.current = chart.addHistogramSeries({
      color: '#26a69a',
      priceFormat: { type: 'volume' },
      priceScaleId: '',
    });

    chart.priceScale('').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });

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
      chart.remove();
      candlestickSeriesRef.current = null;
      volumeSeriesRef.current = null;
    };
  }, []);

  // 종목/기간 변경 시 캔들 데이터 fetch + 차트 갱신
  useEffect(() => {
    if (!stockCode || !candlestickSeriesRef.current) return;
    let cancelled = false;

    async function fetchCandles() {
      try {
        const response = await getStockCandles(stockCode, { period: timeframe });
        if (cancelled) return;
        const period = response?.period ?? timeframe;
        const adapted = (response?.candles ?? []).map((c) => adaptCandle(period, c));

        candlestickSeriesRef.current?.setData(adapted);
        volumeSeriesRef.current?.setData(
          adapted.map((d) => ({
            time: d.time,
            value: d.volume ?? 0,
            color: d.close >= d.open ? 'rgba(239,68,68,0.4)' : 'rgba(59,130,246,0.4)',
          }))
        );
        // 실시간 틱이 들어올 때 합쳐 갱신할 기준 캔들 저장
        lastCandleRef.current = adapted.length > 0 ? { ...adapted[adapted.length - 1] } : null;
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
        const last = lastCandleRef.current;
        if (price == null || !last || !candlestickSeriesRef.current) return;

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

          <button className="tool-btn">이동평균선</button>
          <button className="tool-btn active">볼린저밴드</button>
          <button className="tool-btn">MACD</button>
          <button className="tool-btn active">RSI</button>
          <button className="tool-btn active">거래량</button>
        </div>
        <div className="toolbar-right">
          <button className="tool-btn">지표 추가 ▼</button>
        </div>
      </div>
      <div className="chart-container" ref={chartContainerRef} />
    </div>
  );
}