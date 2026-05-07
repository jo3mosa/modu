import { useState } from 'react';
// 백엔드 strategies 컨트롤러 미구현 상태이므로 일시적으로 mock 동작.
// 백엔드 PR 머지 후 아래 import + 호출 블록 주석을 해제하면 즉시 실 API로 전환된다.
// import { getProfileQuestions, updateProfile, updateRules } from '../api/strategy';
import './RiskManagePage.css';

const RISK_GRADE_LABEL = {
  STABLE: '안정형',
  STABLE_SEEKING: '안정추구형',
  RISK_NEUTRAL: '위험중립형',
  ACTIVE: '적극투자형',
  AGGRESSIVE: '공격투자형',
};

const RISK_GRADE_COLOR = {
  STABLE: '#84cc16',
  STABLE_SEEKING: '#a3e635',
  RISK_NEUTRAL: '#eab308',
  ACTIVE: '#f97316',
  AGGRESSIVE: '#ef4444',
};

// MOCK 설문 문항 (백엔드 strategies API 머지 후 서버 조회로 대체)
const MOCK_QUESTIONS = [
  {
    questionId: 'INVESTMENT_PERIOD',
    text: '투자 기간',
    options: [
      { optionId: 1, text: '단기 (1년 미만)' },
      { optionId: 2, text: '중기 (1~3년)' },
      { optionId: 3, text: '장기 (3년 이상)' },
    ],
  },
  {
    questionId: 'INVESTMENT_GOAL',
    text: '투자 목표',
    options: [
      { optionId: 1, text: '단기 수익 실현' },
      { optionId: 2, text: '장기 자산 증식' },
      { optionId: 3, text: '노후/연금 준비' },
    ],
  },
  {
    questionId: 'RISK_TOLERANCE',
    text: '위험 감수도',
    options: [
      { optionId: 1, text: '안정 추구' },
      { optionId: 2, text: '위험 중립' },
      { optionId: 3, text: '적극 투자' },
    ],
  },
];

// MOCK 분석 결과 (백엔드 머지 후 PATCH 응답으로 대체)
const MOCK_PROFILE_RESULT = {
  riskGrade: 'ACTIVE',
  profileSummary: '공격적인 성향의 투자자입니다. 변동성 기반 매매 전략 수립이 적합합니다!',
};

export default function RiskManagePage() {
  const [isActive, setIsActive] = useState(true);

  // 현재 투자 성향 (mock 초기값)
  // TODO: 백엔드 GET /api/v1/strategies/me/profiles 연동 후 서버에서 조회
  const [profile, setProfile] = useState({
    riskGrade: 'ACTIVE',
    profileSummary: '공격적인 성향의 투자자입니다. 변동성 기반 매매 전략 수립이 적합합니다!',
    principle: 'RSI 70 이상에서는 추격 매수를 하지 않고, 시장 급락 시에는 분할 매수로 접근합니다.',
  });

  const [rules, setRules] = useState({
    takeProfit: 5,
    stopLoss: -3,
    maxDailyOrders: 10,
    maxLossLimit: 500000,
  });

  // 재진단 모달 상태
  const [isModalOpen, setIsModalOpen] = useState(false);
  const questions = MOCK_QUESTIONS;
  const questionsError = null;
  const [modalAnswers, setModalAnswers] = useState({});
  const [modalPrinciple, setModalPrinciple] = useState('');
  const [submittingProfile, setSubmittingProfile] = useState(false);
  const [savingRules, setSavingRules] = useState(false);

  // ── TODO: 백엔드 strategies API 머지 후 아래 블록 해제 (모달 첫 오픈 시 문항 로드) ──
  // const [questions, setQuestions] = useState([]);
  // const [questionsError, setQuestionsError] = useState(null);
  // useEffect(() => {
  //   if (!isModalOpen || questions.length > 0) return;
  //   let cancelled = false;
  //   getProfileQuestions()
  //     .then((data) => { if (!cancelled) setQuestions(data?.questions ?? []); })
  //     .catch((error) => {
  //       if (cancelled) return;
  //       console.error('설문 문항 조회 실패:', error);
  //       setQuestionsError(error.message || '설문 문항을 불러오지 못했습니다.');
  //     });
  //   return () => { cancelled = true; };
  // }, [isModalOpen, questions.length]);
  // ────────────────────────────────────────────────────────────────────────────

  const handleToggleAi = () => setIsActive(!isActive);

  const handleRuleChange = (field, value) => {
    setRules((prev) => ({ ...prev, [field]: value }));
  };

  const handleOpenModal = () => {
    setModalAnswers({});
    setModalPrinciple(profile.principle);
    setIsModalOpen(true);
  };

  const handleCloseModal = () => setIsModalOpen(false);

  const handleSelectOption = (questionId, optionId) => {
    setModalAnswers((prev) => ({ ...prev, [questionId]: optionId }));
  };

  const isModalComplete =
    questions.length > 0 && questions.every((q) => modalAnswers[q.questionId] != null);

  // 모달 저장 (백엔드 미구현이라 mock 동작)
  const handleSaveModal = async () => {
    if (!isModalComplete || submittingProfile) return;
    setSubmittingProfile(true);
    try {
      // ── TODO: 백엔드 strategies API 머지 후 아래 주석 블록 해제 ────────────
      // const answersPayload = questions.map((q) => ({
      //   questionId: q.questionId,
      //   optionId: modalAnswers[q.questionId],
      // }));
      // const result = await updateProfile(answersPayload);
      // setProfile({
      //   riskGrade: result?.riskGrade ?? null,
      //   profileSummary: result?.profileSummary ?? '',
      //   principle: modalPrinciple,
      // });
      // ─────────────────────────────────────────────────────────────────────
      setProfile({
        riskGrade: MOCK_PROFILE_RESULT.riskGrade,
        profileSummary: MOCK_PROFILE_RESULT.profileSummary,
        principle: modalPrinciple,
      });
      setIsModalOpen(false);
    } catch (error) {
      console.error('투자 성향 저장 실패:', error);
      alert(error.message || '투자 성향 저장 중 오류가 발생했습니다.');
    } finally {
      setSubmittingProfile(false);
    }
  };

  // 룰셋 저장 (백엔드 미구현이라 mock 동작)
  const handleSaveAll = async () => {
    if (savingRules) return;
    setSavingRules(true);
    try {
      // ── TODO: 백엔드 strategies API 머지 후 아래 주석 블록 해제 ────────────
      // await updateRules({
      //   principle: profile.principle,
      //   takeProfit: Number(rules.takeProfit),
      //   stopLoss: Number(rules.stopLoss),
      //   positionSize: 'fixed',
      // });
      // ─────────────────────────────────────────────────────────────────────
      alert('변경사항이 임시 저장되었습니다. (백엔드 API 머지 후 실제 반영)');
    } catch (error) {
      console.error('룰셋 저장 실패:', error);
      alert(error.message || '룰셋 저장 중 오류가 발생했습니다.');
    } finally {
      setSavingRules(false);
    }
  };

  const gradeLabel = profile.riskGrade ? RISK_GRADE_LABEL[profile.riskGrade] ?? profile.riskGrade : '미진단';
  const gradeColor = profile.riskGrade ? RISK_GRADE_COLOR[profile.riskGrade] ?? '#888' : '#888';

  return (
    <div className="risk-container">
      {/* 1. 헤더 */}
      <div className="page-header-container">
        <div className="page-title-group">
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
            <span className="profile-label">투자 성향 등급</span>
            <span className="profile-value" style={{ color: gradeColor }}>{gradeLabel}</span>
          </div>
          <div className="profile-card wide">
            <span className="profile-label">성향 요약</span>
            <span className="profile-value">
              {profile.profileSummary || '아직 성향 진단이 완료되지 않았습니다.'}
            </span>
          </div>
        </div>

        <div className="principle-box">
          <h3>나의 매매 원칙</h3>
          <p>{profile.principle ? `"${profile.principle}"` : '아직 등록된 매매 원칙이 없습니다.'}</p>
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
        <button className="save-btn" onClick={handleSaveAll} disabled={savingRules}>
          {savingRules ? '저장 중…' : '변경사항 전체 저장'}
        </button>
      </div>

      {/* 4. 투자 성향 재진단 모달 */}
      {isModalOpen && (
        <div className="modal-overlay">
          <div className="modal-content">
            <div className="modal-header">
              <h2>투자 성향 재진단</h2>
              <button className="close-btn" onClick={handleCloseModal}>&times;</button>
            </div>

            <div className="modal-body">
              {questionsError && <p className="error-text">{questionsError}</p>}

              {!questionsError && questions.length === 0 && (
                <p className="loading-text">설문 문항을 불러오는 중입니다…</p>
              )}

              {questions.map((q, idx) => (
                <div key={q.questionId} className="survey-section" style={idx > 0 ? { marginTop: '2rem' } : undefined}>
                  <h3>{idx + 1}. {q.text}</h3>
                  <div className="options-grid">
                    {q.options.map((opt) => (
                      <button
                        key={opt.optionId}
                        className={`option-btn ${modalAnswers[q.questionId] === opt.optionId ? 'selected' : ''}`}
                        onClick={() => handleSelectOption(q.questionId, opt.optionId)}
                      >
                        {opt.text}
                      </button>
                    ))}
                  </div>
                </div>
              ))}

              <div className="survey-section" style={{ marginTop: '2rem' }}>
                <h3>나의 매매 원칙 수정</h3>
                <textarea
                  className="principle-textarea"
                  value={modalPrinciple}
                  onChange={(e) => setModalPrinciple(e.target.value)}
                  placeholder="예: 3일 연속 하락 시 분할 매수 진행"
                />
              </div>
            </div>

            <div className="modal-footer">
              <button className="modal-btn cancel" onClick={handleCloseModal}>취소</button>
              <button
                className="modal-btn confirm"
                onClick={handleSaveModal}
                disabled={!isModalComplete || submittingProfile}
              >
                {submittingProfile ? '저장 중…' : '적용하기'}
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
