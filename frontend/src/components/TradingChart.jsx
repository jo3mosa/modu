import { useEffect, useRef, useState } from 'react';
import { createChart } from 'lightweight-charts';
// import { getStockCandles } from '../api/market';
import './TradingChart.css';

const TIMEFRAME_OPTIONS = [
  { label: '1분',  value: '1m'  },
  { label: '5분',  value: '5m'  },
  { label: '15분', value: '15m' },
  { label: '60분', value: '60m' },
  { label: '일봉', value: '1D'  },
];

// ── MOCK 더미 데이터 (백엔드 연동 후 삭제 예정) ───────────────────────────────
const generateDummyData = () => {
  const initialDate = new Date();
  initialDate.setUTCDate(initialDate.getUTCDate() - 100);
  const data = [];
  let currentPrice = 75000;

  for (let i = 0; i < 100; i++) {
    const open = currentPrice;
    const high = open + Math.random() * 2000;
    const low = open - Math.random() * 2000;
    const close = low + Math.random() * (high - low);
    const d = new Date(initialDate.getTime() + i * 86400000);
    const timeStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    data.push({ time: timeStr, open, high, low, close });
    currentPrice = close;
  }
  return data;
};

const generateVolumeData = (candleData) =>
  candleData.map(d => ({
    time: d.time,
    value: Math.random() * 500000 + 100000,
    color: d.close >= d.open ? 'rgba(239, 68, 68, 0.4)' : 'rgba(59, 130, 246, 0.4)',
  }));
// ─────────────────────────────────────────────────────────────────────────────

export default function TradingChart({ stockCode }) {
  const chartContainerRef = useRef(null);
  const candlestickSeriesRef = useRef(null);
  const volumeSeriesRef = useRef(null);
  const [timeframe, setTimeframe] = useState('1D');

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

    // ── MOCK 데이터 세팅 (연동 시 아래 3줄 삭제) ──────────────────────────
    const data = generateDummyData();
    candlestickSeriesRef.current.setData(data);
    candlestickSeriesRef.current.setMarkers([
      { time: data[data.length - 25].time, position: 'belowBar', color: '#ef4444', shape: 'arrowUp',   text: '매수'    },
      { time: data[data.length - 15].time, position: 'aboveBar', color: '#3b82f6', shape: 'arrowDown', text: '매도'    },
      { time: data[data.length - 5].time,  position: 'belowBar', color: '#ef4444', shape: 'arrowUp',   text: 'AI 매수' },
    ]);
    volumeSeriesRef.current.setData(generateVolumeData(data));
    // ─────────────────────────────────────────────────────────────────────

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

  // ── 연동 시 아래 블록 해제 (과거 캔들 데이터 REST) ────────────────────────
  // useEffect(() => {
  //   if (!stockCode || !candlestickSeriesRef.current) return;
  //   async function fetchCandles() {
  //     try {
  //       const candles = await getStockCandles(stockCode, timeframe);
  //       // candles: [{ time, open, high, low, close, volume }, ...]
  //       candlestickSeriesRef.current.setData(candles);
  //       volumeSeriesRef.current.setData(
  //         candles.map(d => ({
  //           time: d.time,
  //           value: d.volume,
  //           color: d.close >= d.open ? 'rgba(239,68,68,0.4)' : 'rgba(59,130,246,0.4)',
  //         }))
  //       );
  //     } catch (error) {
  //       console.error('캔들 데이터 로드 실패:', error);
  //     }
  //   }
  //   fetchCandles();
  // }, [stockCode, timeframe]);

  // ── 연동 시 아래 블록 해제 (실시간 현재가 WebSocket) ──────────────────────
  // useEffect(() => {
  //   if (!stockCode || !candlestickSeriesRef.current) return;
  //   const ws = new WebSocket(`/ws/stocks/${stockCode}/price`);
  //   ws.onmessage = (event) => {
  //     const tick = JSON.parse(event.data);
  //     // tick: { time, open, high, low, close }
  //     candlestickSeriesRef.current.update(tick);
  //   };
  //   return () => ws.close();
  // }, [stockCode]);

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
