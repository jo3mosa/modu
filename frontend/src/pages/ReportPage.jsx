import { useState } from 'react';
import Highcharts from 'highcharts';
import HighchartsReactPkg from 'highcharts-react-official';
import highcharts3d from 'highcharts/highcharts-3d';
import { ChevronDown, ChevronUp } from 'lucide-react';
import './ReportPage.css';

const HighchartsReact = HighchartsReactPkg.default || HighchartsReactPkg;

if (typeof Highcharts === 'object') {
  if (typeof highcharts3d === 'function') {
    highcharts3d(Highcharts);
  } else if (highcharts3d && typeof highcharts3d.default === 'function') {
    highcharts3d.default(Highcharts);
  }
}

// 임시 더미
const MOCK_MARKET_BRIEFING = {
  summary: "오늘 시장은 외국인과 기관의 쌍끌이 매수세가 유입되며 IT 및 반도체 섹터를 중심으로 강한 상승 흐름을 보였습니다. 반면 금융과 필수소비재 섹터는 금리 인하 기대감 축소로 인해 약보합세를 나타내고 있습니다. 전반적인 투자 심리는 '탐욕(Greed)' 구간에 진입했습니다.",
  tags: [
    { label: '외국인 순매수', type: 'positive' },
    { label: 'IT/반도체 강세', type: 'positive' },
    { label: '금융주 약세', type: 'negative' },
    { label: 'Fear & Greed: 68', type: 'info' }
  ]
};

const CHART_COLORS = ['#3b82f6', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981'];

const MOCK_SECTOR_DATA = [
  { name: 'IT/반도체', y: 65, color: CHART_COLORS[0] },
  { name: '금융', y: 10, color: CHART_COLORS[1] },
  { name: '소비재', y: 15, color: CHART_COLORS[2] },
  { name: '에너지/화학', y: 5, color: CHART_COLORS[3] },
  { name: '바이오/헬스', y: 5, color: CHART_COLORS[4] }
];

const MOCK_TREND_DATA = {
  categories: ['1주전', '6일전', '5일전', '4일전', '3일전', '2일전', '어제', '오늘'],
  myYield: [0, 1.2, -0.5, 2.1, 4.5, 3.8, 5.2, 7.1],
  kospiYield: [0, 0.5, 0.2, 0.8, 1.1, 1.0, 1.5, 2.0]
};

const MOCK_TRADE_LOGS = [
  {
    id: 1,
    type: 'BUY',
    stock: '삼성전자',
    price: 74500,
    qty: 10,
    time: '2026-05-03 10:30',
    reason: 'RSI 지표가 30 이하로 과매도 구간에 진입하였으며, 외국인 순매수세가 3일 연속 유입되는 것을 확인하여 분할 매수 1차 진입을 결정했습니다.'
  },
  {
    id: 2,
    type: 'SELL',
    stock: 'SK하이닉스',
    price: 149000,
    qty: 5,
    time: '2026-05-02 09:15',
    reason: '설정된 목표 수익률 5%를 초과 달성하였으며, 볼린저 밴드 상단 이탈 및 단기 저항선 도달을 확인하여 수익 실현을 위해 매도 처리했습니다.'
  },
  {
    id: 3,
    type: 'SELL',
    stock: '카카오',
    price: 45000,
    qty: 20,
    time: '2026-05-01 14:20',
    reason: '설정된 최대 허용 손실률(-3%)에 도달하여, 추가적인 하락 리스크를 방어하기 위해 설정된 리스크 관리 룰셋에 따라 기계적 손절매를 집행했습니다.'
  }
];

const PERIOD_OPTIONS = [
  { value: '1W', label: '1주일' },
  { value: '1M', label: '1개월' },
  { value: '3M', label: '3개월' },
  { value: '1Y', label: '1년' }
];

const MOCK_HABIT_SUMMARY = {
  pnl: 540000,
  pnlRate: 5.4,
  title: "안정적인 수익 창출 중이나, 간헐적 과매매 주의",
  desc: "이번 달은 손절 원칙을 잘 지켜 전반적으로 안정적인 수익을 누적했습니다. 다만 지난주 급락장에서 하루 10회 이상 매매하는 등 일시적인 뇌동매매 징후가 포착되었습니다. 규칙적인 매매 빈도를 유지하세요."
};

export default function ReportPage() {
  const [period, setPeriod] = useState('1M');
  const [briefing] = useState(MOCK_MARKET_BRIEFING);
  const [logs] = useState(MOCK_TRADE_LOGS);
  const [expandedLogId, setExpandedLogId] = useState(null);

  const toggleLog = (id) => {
    setExpandedLogId(prev => (prev === id ? null : id));
  };

  // 1. 도넛 차트 -> 종목 비중
  const donutOptions = {
    chart: {
      type: 'pie',
      options3d: { enabled: true, alpha: 30, beta: 0 },
      backgroundColor: 'transparent',
      style: { fontFamily: "'Pretendard', sans-serif" },
      margin: [0, 0, 0, 0]
    },
    title: { text: null },
    credits: { enabled: false },
    tooltip: {
      pointFormat: '<span style="color:{point.color}">\u25CF</span> {series.name}: <b>{point.y}%</b><br/>'
    },
    plotOptions: {
      pie: {
        innerSize: 100,
        depth: 35,
        size: '80%',
        center: ['50%', '50%'],
        dataLabels: {
          enabled: true,
          format: '<b>{point.name}</b><br>{point.y}%',
          color: '#fff',
          style: { textOutline: 'none', fontWeight: '600' }
        },
        borderWidth: 0,
        showInLegend: false
      }
    },
    series: [{
      name: '섹터 비중',
      data: MOCK_SECTOR_DATA
    }]
  };

  // 2. 라인 차트 -> 수익률 추이
  const trendOptions = {
    chart: {
      type: 'spline',
      backgroundColor: 'transparent',
      style: { fontFamily: "'Pretendard', sans-serif" }
    },
    title: { text: null },
    xAxis: {
      categories: MOCK_TREND_DATA.categories,
      labels: { style: { color: '#888' } }
    },
    yAxis: {
      title: { text: '누적 수익률 (%)', style: { color: '#888' } },
      labels: { style: { color: '#888' }, format: '{value}%' },
      gridLineColor: 'rgba(255,255,255,0.05)'
    },
    tooltip: { shared: true, valueSuffix: '%' },
    legend: { itemStyle: { color: '#ccc' }, itemHoverStyle: { color: '#fff' } },
    series: [
      {
        name: '내 포트폴리오',
        data: MOCK_TREND_DATA.myYield,
        color: '#84cc16',
        lineWidth: 3,
        marker: { symbol: 'circle' }
      },
      {
        name: 'KOSPI 벤치마크',
        data: MOCK_TREND_DATA.kospiYield,
        color: '#6366f1',
        dashStyle: 'ShortDash',
        marker: { enabled: false }
      }
    ],
    credits: { enabled: false }
  };

  return (
    <div className="report-container" style={{ padding: '0 0.5rem' }}>
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>리포트</h1>
          <p>내 포트폴리오 분석 결과와 매매 기록을 확인하세요.</p>
        </div>
        <div className="period-filter">
          {PERIOD_OPTIONS.map(opt => (
            <button 
              key={opt.value} 
              className={`period-btn ${period === opt.value ? 'active' : ''}`}
              onClick={() => setPeriod(opt.value)}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 1. 시장 브리핑 */}
      <div className="report-panel market-briefing">
        <h2>오늘의 AI 시장 브리핑</h2>
        <div className="briefing-content">
          <p className="briefing-text">{briefing.summary}</p>
          <div className="market-tags">
            {briefing.tags.map((tag, idx) => (
              <span key={idx} className={`market-tag ${tag.type}`}>
                #{tag.label}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* 2. 포트폴리오 시각화 */}
      <div className="chart-grid">
        <div className="chart-box">
          <h3>섹터별 자산 비중</h3>
          <div className="chart-wrapper">
            <HighchartsReact
              highcharts={Highcharts}
              options={donutOptions}
              containerProps={{ style: { width: '100%', height: '100%' } }}
            />
          </div>
        </div>

        <div className="chart-box">
          <h3>최근 누적 수익률 추이 (vs 코스피)</h3>
          <div className="chart-wrapper">
            <HighchartsReact
              highcharts={Highcharts}
              options={trendOptions}
              containerProps={{ style: { width: '100%', height: '100%' } }}
            />
          </div>
        </div>
      </div>

      {/* 3. 매매 로그 + 근거 */}
      <div className="report-panel">
        <h2>매매 로그 · AI 결정 근거</h2>

        {/* AI 매매 습관 종합 요약 */}
        <div className="habit-summary-box">
          <div className="habit-pnl">
            <span className="pnl-label">기간 내 실현 손익</span>
            <span className="pnl-value">
              +{MOCK_HABIT_SUMMARY.pnl.toLocaleString()}원 
              <span className="pnl-rate">(+{MOCK_HABIT_SUMMARY.pnlRate}%)</span>
            </span>
          </div>
          <div className="habit-text">
            <h4>{MOCK_HABIT_SUMMARY.title}</h4>
            <p>{MOCK_HABIT_SUMMARY.desc}</p>
          </div>
        </div>

        <div className="trade-log-list">
          {logs.map((log) => {
            const isExpanded = expandedLogId === log.id;
            return (
              <div key={log.id} className={`trade-log-item ${isExpanded ? 'expanded' : ''}`}>
                <div className="log-header" onClick={() => toggleLog(log.id)}>
                  <div className="log-header-left">
                    <span className={`log-badge ${log.type.toLowerCase()}`}>
                      {log.type === 'BUY' ? '매수' : '매도'}
                    </span>
                    <span className="log-stock">{log.stock}</span>
                    <span className="log-price">{log.price.toLocaleString()}원 · {log.qty}주</span>
                  </div>
                  <div className="log-header-right">
                    <span className="log-time">{log.time}</span>
                    {isExpanded ? <ChevronUp size={20} color="#888" /> : <ChevronDown size={20} color="#888" />}
                  </div>
                </div>

                {isExpanded && (
                  <div className="log-reason-box">
                    <h4>AI 판단 근거</h4>
                    <p>{log.reason}</p>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

    </div>
  );
}
