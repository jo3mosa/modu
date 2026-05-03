import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Highcharts from 'highcharts';
import HighchartsReactPkg from 'highcharts-react-official';
const HighchartsReact = HighchartsReactPkg.default || HighchartsReactPkg;
import highcharts3d from 'highcharts/highcharts-3d';
import TutorialOverlay from '../components/TutorialOverlay';
import './DashboardPage.css';

if (typeof Highcharts === 'object') {
  if (typeof highcharts3d === 'function') {
    highcharts3d(Highcharts);
  } else if (highcharts3d && typeof highcharts3d.default === 'function') {
    highcharts3d.default(Highcharts);
  }
}

const MOCK_SUMMARY = {
  totalAssets: 600000,
  principal: 570000,
  totalPnL: 30000,
  returnRate: 5.26,
  availableCash: 200000
};

const MOCK_HOLDINGS = [
  { name: '삼성전자', code: '005930', quantity: 3, avgPrice: 70000, currentPrice: 74900, pnl: 14700, returnRate: 7.00 },
  { name: '한화에어로스페이스', code: '012450', quantity: 1, avgPrice: 60000, currentPrice: 85300, pnl: 25300, returnRate: 42.16 },
  { name: '카카오', code: '035720', quantity: 2, avgPrice: 50000, currentPrice: 45000, pnl: -10000, returnRate: -10.00 },
];

const MOCK_AI_STATUS = {
  isActive: true,
  strategy: '단기 수익 실현',
  riskLevel: '보통'
};

const MOCK_LOGS = [
  { id: 1, type: 'BUY', stock: '삼성전자', price: 74500, qty: 10, time: '10:30', status: 'SUCCESS' },
  { id: 2, type: 'SELL', stock: 'SK하이닉스', price: 149000, qty: 5, time: '09:15', status: 'SUCCESS' },
  { id: 3, type: 'ERROR', stock: 'NAVER', desc: '잔고 부족으로 예약 매수 실패', time: '09:05', status: 'FAIL' },
];

const CHART_COLORS = ['#3b82f6', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981', '#84cc16'];

export default function DashboardPage() {
  const navigate = useNavigate();
  const [showTutorial, setShowTutorial] = useState(false);

  const [summary] = useState(MOCK_SUMMARY);
  const [holdings] = useState(MOCK_HOLDINGS);
  const [aiStatus, setAiStatus] = useState(MOCK_AI_STATUS);
  const [logs] = useState(MOCK_LOGS);

  useEffect(() => {
    setShowTutorial(true);
  }, []);

  const handleCloseTutorial = () => setShowTutorial(false);

  const toggleAiStatus = () => {
    setAiStatus(prev => ({ ...prev, isActive: !prev.isActive }));
  };

  const formatNumber = (num) => num.toLocaleString();
  const getColorClass = (val) => {
    if (val > 0) return 'text-red';
    if (val < 0) return 'text-blue';
    return '';
  };

  // 도넛 차트 데이터 포맷팅
  const chartData = holdings.map((h, i) => ({
    name: h.name,
    y: h.quantity * h.currentPrice,
    color: CHART_COLORS[i % CHART_COLORS.length],
    quantity: h.quantity
  }));
  chartData.push({ name: '예수금', y: summary.availableCash, color: '#84cc16', quantity: null });

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
        center: ['50%', '35%'], // 카드 섹션 중앙에 위치
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
        {/* 좌측: 포트폴리오 메인 (70%) */}
        <div className="dashboard-main">
          {/* 자산 요약 및 차트 */}
          <div className="panel overview-panel">
            <div className="overview-text">
              <h2>포트폴리오 요약</h2>
              <div className="asset-huge">
                <span className="label">총 자산</span>
                <div className="value-row">
                  <span className="value">{formatNumber(summary.totalAssets)}</span>
                  <span className="unit">원</span>
                </div>
              </div>

              <div className="asset-details">
                <div className="detail-item">
                  <span className="detail-label">총 평가 손익</span>
                  <span className={`detail-value ${getColorClass(summary.totalPnL)}`}>
                    {summary.totalPnL > 0 ? '+' : ''}{formatNumber(summary.totalPnL)}원
                    <span className="rate">({summary.returnRate > 0 ? '+' : ''}{summary.returnRate}%)</span>
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">투자 원금</span>
                  <span className="detail-value">{formatNumber(summary.principal)}원</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">가용 예수금</span>
                  <span className="detail-value cash">{formatNumber(summary.availableCash)}원</span>
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
                    <tr key={idx} onClick={() => navigate('/trading')} className="clickable-row">
                      <td className="col-name">
                        <span className="stock-name">{item.name}</span>
                        <span className="stock-code">{item.code}</span>
                      </td>
                      <td>{formatNumber(item.quantity)}주</td>
                      <td>{formatNumber(item.avgPrice)}원</td>
                      <td>{formatNumber(item.currentPrice)}원</td>
                      <td className={getColorClass(item.pnl)}>
                        {item.pnl > 0 ? '+' : ''}{formatNumber(item.pnl)}원
                      </td>
                      <td className={getColorClass(item.returnRate)}>
                        {item.returnRate > 0 ? '+' : ''}{item.returnRate}%
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
                  <span className="info-value">{aiStatus.strategy}</span>
                </div>
                <div className="info-row">
                  <span className="info-label">위험 수준</span>
                  <span className="info-value">{aiStatus.riskLevel}</span>
                </div>
              </div>
            </div>
          </div>

          {/* 최근 매매 로그 */}
          <div className="panel logs-panel">
            <h2>최근 매매 로그</h2>
            <div className="logs-list">
              {logs.map((log) => (
                <div key={log.id} className="log-item">
                  <div className={`log-icon ${log.type.toLowerCase()}`}>
                    {log.type === 'BUY' ? '매수' : log.type === 'SELL' ? '매도' : '경고'}
                  </div>
                  <div className="log-content">
                    <div className="log-top">
                      <span className="log-stock">{log.stock}</span>
                      <span className="log-time">{log.time}</span>
                    </div>
                    <div className="log-bottom">
                      {log.type === 'ERROR' ? (
                        <span className="text-red">{log.desc}</span>
                      ) : (
                        <span>{formatNumber(log.price)}원 · {log.qty}주 체결</span>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}