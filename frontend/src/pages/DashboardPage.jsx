import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import TutorialOverlay from '../components/TutorialOverlay';
import './DashboardPage.css';

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

  // 파이 차트 데이터 생성 (종목 평가금액 + 예수금)
  const pieData = holdings.map(h => ({
    name: h.name,
    value: h.quantity * h.currentPrice,
    quantity: h.quantity
  }));
  pieData.push({ name: '예수금(현금)', value: summary.availableCash, quantity: null });
  
  // 비중 내림차순 정렬
  pieData.sort((a, b) => b.value - a.value);

  // 도넛 차트 커스텀 툴팁
  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const data = payload[0].payload;
      return (
        <div style={{ background: 'rgba(0,0,0,0.85)', padding: '10px 15px', borderRadius: '8px', border: '1px solid rgba(255,255,255,0.15)', boxShadow: '0 4px 12px rgba(0,0,0,0.3)' }}>
          <div style={{ color: payload[0].fill, fontWeight: 700, marginBottom: '6px', fontSize: '1.05rem' }}>{data.name}</div>
          <div style={{ color: '#fff', fontSize: '0.95rem', fontWeight: 600 }}>
            {data.quantity !== null ? `${formatNumber(data.quantity)}주 · ` : ''}
            {formatNumber(data.value)}원
          </div>
        </div>
      );
    }
    return null;
  };

  return (
    <div className="dashboard-container">
      {showTutorial && <TutorialOverlay onClose={handleCloseTutorial} />}

      <div className="dashboard-header">
        <h1>대시보드</h1>
        <p>전체 자산 현황과 AI 매매 상태를 실시간으로 모니터링하세요.</p>
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
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pieData}
                    innerRadius="65%"
                    outerRadius="90%"
                    paddingAngle={4}
                    dataKey="value"
                    stroke="none"
                    animationBegin={0}
                    animationDuration={1500}
                    isAnimationActive={true}
                  >
                    {pieData.map((entry, index) => (
                      <Cell 
                        key={`cell-${index}`} 
                        fill={entry.name.includes('예수금') ? '#84cc16' : CHART_COLORS[index % CHART_COLORS.length]} 
                      />
                    ))}
                  </Pie>
                  <Tooltip content={<CustomTooltip />} />
                </PieChart>
              </ResponsiveContainer>
              {/* 차트 중앙 텍스트 */}
              <div className="chart-center-text">
                <span className="chart-center-label">총 자산</span>
                <span className="chart-center-value">
                  {formatNumber(summary.totalAssets / 10000)}만<span style={{fontSize:'1.1rem', marginLeft:'2px', color:'#aaa', fontWeight:600}}>원</span>
                </span>
              </div>
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

        {/* 우측: AI 관제 사이드바 (30%) */}
        <div className="dashboard-side">
          {/* AI 미니 컨트롤 */}
          <div className="panel ai-mini-panel">
            <h2>AI 자동매매 관제</h2>
            <div className="ai-mini-content">
              <div className="ai-status-row">
                <div className="ai-status-text">
                  <span className={`status-badge ${aiStatus.isActive ? 'active' : 'inactive'}`}>
                    {aiStatus.isActive ? '가동 중' : '중단됨'}
                  </span>
                </div>
                {/* 커스텀 iOS 스타일 토글 스위치 */}
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

          {/* 최근 거래 로그 */}
          <div className="panel logs-panel">
            <h2>최근 시스템 로그</h2>
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