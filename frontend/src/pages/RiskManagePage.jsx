import { useState } from 'react';
import './RiskManagePage.css';

export default function RiskManagePage() {
  // 1. 상태
  const [isActive, setIsActive] = useState(true);

  // 현재 설정된 투자 성향 + 원칙
  const [profile, setProfile] = useState({
    horizon: '단기 (1년 미만)',
    goal: '단기 수익 실현',
    risk: '적극 투자 (고위험 고수익)',
    principle: 'RSI 70 이상에서는 추격 매수를 하지 않고, 시장 급락 시에는 분할 매수로 접근합니다.'
  });

  // 정량적 룰셋
  const [rules, setRules] = useState({
    takeProfit: 5,
    stopLoss: -3,
    maxDailyOrders: 10,
    maxLossLimit: 500000
  });

  // 모달 제어 상태
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalData, setModalData] = useState({ ...profile });

  const getRiskColor = (risk) => {
    if (risk.includes('안정')) return '#84cc16';
    if (risk.includes('중립')) return '#eab308';
    return '#ef4444';
  };

  // 2. 핸들러
  const handleToggleAi = () => setIsActive(!isActive);

  const handleRuleChange = (field, value) => {
    setRules(prev => ({ ...prev, [field]: value }));
  };

  const handleOpenModal = () => {
    setModalData({ ...profile });
    setIsModalOpen(true);
  };

  const handleCloseModal = () => setIsModalOpen(false);

  const handleSaveModal = () => {
    setProfile(modalData);
    setIsModalOpen(false);
  };

  const handleSaveAll = () => {
    alert('변경사항이 성공적으로 저장되었습니다!');
    // API Call: PUT /api/v1/strategies/me/profiles & /api/v1/strategies/me/rules
  };

  return (
    <div className="risk-container">
      {/* 1. 헤더 */}
      <div className="risk-header">
        <div className="risk-title">
          <h1>리스크 관리</h1>
          <p>내 투자 성향을 확인하고 매매 전략을 조정해보세요!</p>
        </div>

        <div className="risk-global-controls">
          <div className="control-item">
            <span className="control-label" style={{ color: isActive ? '#84cc16' : '#888' }}>
              AI 자동매매 {isActive ? 'ON' : 'OFF'}
            </span>
            <div className={`toggle-switch ${isActive ? 'on' : 'off'}`} onClick={handleToggleAi}>
              <div className="toggle-knob"></div>
            </div>
          </div>
        </div>
      </div>

      {/* 2. 투자 성향 + 목표 관리 */}
      <div className="risk-panel">
        <h2>
          나의 투자 성향
          <button className="edit-btn" onClick={handleOpenModal}>성향 재진단</button>
        </h2>

        <div className="profile-cards">
          <div className="profile-card">
            <span className="profile-label">투자 기간</span>
            <span className="profile-value">{profile.horizon}</span>
          </div>
          <div className="profile-card">
            <span className="profile-label">투자 목표</span>
            <span className="profile-value">{profile.goal}</span>
          </div>
          <div className="profile-card">
            <span className="profile-label">위험 감수도</span>
            <span className="profile-value" style={{ color: getRiskColor(profile.risk) }}>{profile.risk}</span>
          </div>
        </div>

        <div className="principle-box">
          <h3>나의 매매 원칙</h3>
          <p>"{profile.principle}"</p>
        </div>
      </div>

      {/* 3. 정량적 리스크 룰셋 설정 */}
      <div className="risk-panel">
        <h2>안전 장치 룰셋 설정</h2>
        <div className="rules-grid">
          <div className="input-group">
            <label>익절 기준 (목표 수익률)</label>
            <div className="input-with-unit">
              <input
                type="number"
                value={rules.takeProfit}
                onChange={(e) => handleRuleChange('takeProfit', e.target.value)}
              />
              <span className="unit">%</span>
            </div>
          </div>

          <div className="input-group">
            <label>손절 기준 (최대 허용 손실률)</label>
            <div className="input-with-unit">
              <input
                type="number"
                value={rules.stopLoss}
                onChange={(e) => handleRuleChange('stopLoss', e.target.value)}
              />
              <span className="unit">%</span>
            </div>
          </div>

          <div className="input-group">
            <label>1일 최대 주문 횟수 제한</label>
            <div className="input-with-unit">
              <input
                type="number"
                value={rules.maxDailyOrders}
                onChange={(e) => handleRuleChange('maxDailyOrders', e.target.value)}
              />
              <span className="unit">회</span>
            </div>
          </div>

          <div className="input-group">
            <label>1일 최대 허용 손실 금액</label>
            <div className="input-with-unit">
              <input
                type="number"
                value={rules.maxLossLimit}
                onChange={(e) => handleRuleChange('maxLossLimit', e.target.value)}
                step="10000"
              />
              <span className="unit">원</span>
            </div>
          </div>
        </div>
      </div>

      <div className="action-footer">
        <button className="save-btn" onClick={handleSaveAll}>변경사항 전체 저장</button>
      </div>

      {/* 4. 투자 성향 재진단 모달 팝업 */}
      {isModalOpen && (
        <div className="modal-overlay">
          <div className="modal-content">
            <div className="modal-header">
              <h2>투자 성향 재진단</h2>
              <button className="close-btn" onClick={handleCloseModal}>&times;</button>
            </div>

            <div className="modal-body">
              <div className="survey-section">
                <h3>투자 기간</h3>
                <div className="options-grid">
                  {['단기 (1년 미만)', '중기 (1~3년)', '장기 (3년 이상)'].map(opt => (
                    <button
                      key={opt}
                      className={`option-btn ${modalData.horizon === opt ? 'selected' : ''}`}
                      onClick={() => setModalData({ ...modalData, horizon: opt })}
                    >
                      {opt}
                    </button>
                  ))}
                </div>
              </div>

              <div className="survey-section" style={{ marginTop: '2rem' }}>
                <h3>투자 목표</h3>
                <div className="options-grid">
                  {['단기 수익 실현', '장기 자산 증식', '노후/연금 준비'].map(opt => (
                    <button
                      key={opt}
                      className={`option-btn ${modalData.goal === opt ? 'selected' : ''}`}
                      onClick={() => setModalData({ ...modalData, goal: opt })}
                    >
                      {opt}
                    </button>
                  ))}
                </div>
              </div>

              <div className="survey-section" style={{ marginTop: '2rem' }}>
                <h3>위험 감수도</h3>
                <div className="options-grid">
                  {['안정 추구', '위험 중립', '적극 투자'].map(opt => (
                    <button
                      key={opt}
                      className={`option-btn ${modalData.risk === opt ? 'selected' : ''}`}
                      onClick={() => setModalData({ ...modalData, risk: opt })}
                    >
                      {opt}
                    </button>
                  ))}
                </div>
              </div>

              <div className="survey-section" style={{ marginTop: '2rem' }}>
                <h3>나의 매매 원칙 수정</h3>
                <textarea
                  className="principle-textarea"
                  value={modalData.principle}
                  onChange={(e) => setModalData({ ...modalData, principle: e.target.value })}
                  placeholder="예: 3일 연속 하락 시 분할 매수 진행"
                />
              </div>
            </div>

            <div className="modal-footer">
              <button className="modal-btn cancel" onClick={handleCloseModal}>취소</button>
              <button className="modal-btn confirm" onClick={handleSaveModal}>적용하기</button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}