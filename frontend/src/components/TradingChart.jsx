import { useEffect, useRef } from 'react';
import { createChart } from 'lightweight-charts';
import './TradingChart.css';

// 더미 캔들스틱 데이터 생성 함수
const generateDummyData = () => {
  let initialDate = new Date();
  initialDate.setUTCDate(initialDate.getUTCDate() - 100);
  const data = [];
  let currentPrice = 75000;

  for (let i = 0; i < 100; i++) {
    const open = currentPrice;
    const high = open + Math.random() * 2000;
    const low = open - Math.random() * 2000;
    const close = low + Math.random() * (high - low);

    // time: string 'YYYY-MM-DD'
    const d = new Date(initialDate.getTime() + i * 86400000);
    const timeStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

    data.push({
      time: timeStr,
      open, high, low, close
    });
    currentPrice = close;
  }
  return data;
};

// 현재 차트 -> 랜덤 더미 데이터
const generateVolumeData = (candleData) => {
  return candleData.map(d => ({
    time: d.time,
    value: Math.random() * 500000 + 100000,
    color: d.close >= d.open ? 'rgba(239, 68, 68, 0.4)' : 'rgba(59, 130, 246, 0.4)' // 상승 -> 빨강, 하락 -> 파랑
  }));
};

export default function TradingChart() {
  const chartContainerRef = useRef(null);

  // 연동 시 주석 해제 필요 !!
  // 1. 과거 데이터 불러오기 (REST API)
  /*
  useEffect(() => {
    const fetchHistoricalData = async () => {
      try {
        const response = await fetch('/api/v1/markets/stocks/005930/candles?timeframe=1D'); // ex) 삼성전자 일봉
        const data = await response.json();
        // data 형식: [{ time: '2023-10-01', open: 75000, high: 76000, low: 74500, close: 75500 }, ...]
        candlestickSeries.current.setData(data);
      } catch (error) {
        console.error("차트 데이터 로드 실패:", error);
      }
    };
    // fetchHistoricalData();
  }, []);

  // 2. 실시간 현재가 업데이트 (WebSocket)
  /*
  useEffect(() => {
    const ws = new WebSocket('ws://api.modu.com/ws/stocks/005930/price');
    
    ws.onmessage = (event) => {
      const liveData = JSON.parse(event.data);
      // liveData 형식: { time: '2023-10-01', open: 75000, high: 76000, low: 74500, close: 75500 }
      candlestickSeries.current.update(liveData);
    };

    return () => ws.close();
  }, []);
  */

  useEffect(() => {
    if (!chartContainerRef.current) return;

    // 차트 생성
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

    // 캔들 색상
    const candlestickSeries = chart.addCandlestickSeries({
      upColor: '#ef4444',
      downColor: '#3b82f6',
      borderVisible: false,
      wickUpColor: '#ef4444',
      wickDownColor: '#3b82f6',
    });

    const data = generateDummyData();
    candlestickSeries.setData(data);

    // AI + 수동 매매 마커
    candlestickSeries.setMarkers([
      { time: data[data.length - 25].time, position: 'belowBar', color: '#ef4444', shape: 'arrowUp', text: '매수' }, // 수동 매수
      { time: data[data.length - 15].time, position: 'aboveBar', color: '#3b82f6', shape: 'arrowDown', text: '매도' }, // 수동 매도
      { time: data[data.length - 5].time, position: 'belowBar', color: '#ef4444', shape: 'arrowUp', text: 'AI 매수' }, // AI 매수
    ]);

    // 거래량
    const volumeSeries = chart.addHistogramSeries({
      color: '#26a69a',
      priceFormat: { type: 'volume' },
      priceScaleId: '', // 오버레이 설정
    });

    // 거래량 차트 높이 제한
    chart.priceScale('').applyOptions({
      scaleMargins: {
        top: 0.8,
        bottom: 0,
      },
    });

    volumeSeries.setData(generateVolumeData(data));

    const handleResize = () => {
      chart.applyOptions({ width: chartContainerRef.current.clientWidth, height: chartContainerRef.current.clientHeight });
    };

    window.addEventListener('resize', handleResize);
    setTimeout(handleResize, 100);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
    };
  }, []);

  return (
    <div className="chart-wrapper">
      <div className="chart-toolbar">
        <div className="toolbar-left">
          <button className="tool-btn">1분</button>
          <button className="tool-btn">5분</button>
          <button className="tool-btn">15분</button>
          <button className="tool-btn">60분</button>
          <button className="tool-btn active" style={{ marginRight: '1rem' }}>일봉</button>

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
