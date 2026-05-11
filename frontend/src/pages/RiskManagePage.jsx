import { useEffect, useState } from 'react';
import { getProfile, getProfileQuestions, getRules, updateProfile, updateRules } from '../api/strategy';
import './RiskManagePage.css';

const RISK_LEVEL_LABEL = {
  STABLE: '안정형',
  STABLE_SEEKING: '안정추구형',
  RISK_NEUTRAL: '위험중립형',
  ACTIVE: '적극투자형',
  AGGRESSIVE: '공격투자형',
};

const RISK_LEVEL_COLOR = {
  STABLE: '#84cc16',
  STABLE_SEEKING: '#a3e635',
  RISK_NEUTRAL: '#eab308',
  ACTIVE: '#f97316',
  AGGRESSIVE: '#ef4444',
};

export default function RiskManagePage() {
  const [isActive, setIsActive] = useState(true);

  // 현재 투자 성향 
  const [profile, setProfile] = useState({
    riskLevel: null,
    profileSummary: '',
    principle: '',
  });

  const [rules, setRules] = useState({
    takeProfit: 5,
    stopLoss: -3,
    maxDailyOrders: 10,
    maxLossLimit: 500000,
  });

  // 페이지 진입 시 GET /me/rules로 초기화
  // 이후 PUT 응답의 version을 갱신 보관해 다음 요청에 재전송
  const [ruleVersion, setRuleVersion] = useState(0);

  // 재진단 모달 상태
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [questions, setQuestions] = useState([]);
  const [questionsError, setQuestionsError] = useState(null);
  const [modalAnswers, setModalAnswers] = useState({}); // { [questionId]: optionId(string) }
  const [modalPrinciple, setModalPrinciple] = useState('');
  const [submittingProfile, setSubmittingProfile] = useState(false);
  const [savingRules, setSavingRules] = useState(false);

  // 페이지 진입 시 현재 룰셋 초기 조회
  useEffect(() => {
    let cancelled = false;
    getRules()
      .then((data) => {
        if (cancelled || !data) return;
        setRules({
          takeProfit: data.takeProfitRate ?? 5,
          stopLoss: -(data.stopLossRate ?? 3),
          maxDailyOrders: data.maxDailyOrderCount ?? 10,
          maxLossLimit: data.maxDailyLossAmount ?? 500000,
        });
        setRuleVersion(data.version ?? 0);
      })
      .catch((error) => {
        if (cancelled) return;
        if (error.status !== 404) {
          console.error('룰셋 조회 실패:', error);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 페이지 진입 시 현재 투자 성향 초기 조회)
  useEffect(() => {
    let cancelled = false;
    getProfile()
      .then((data) => {
        if (cancelled || !data) return;
        setProfile({
          riskLevel: data.riskLevel ?? null,
          profileSummary: data.profileSummary ?? '',
          principle: data.freeText ?? '',
        });
      })
      .catch((error) => {
        if (cancelled) return;
        if (error.status !== 404 && error.errorCode !== 'INVEST_001') {
          console.error('투자 성향 조회 실패:', error);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 모달이 처음 열릴 때 설문 문항 로드
  useEffect(() => {
    if (!isModalOpen || questions.length > 0) return;
    let cancelled = false;
    getProfileQuestions()
      .then((data) => {
        if (cancelled) return;
        setQuestions(data?.questions ?? []);
      })
      .catch((error) => {
        if (cancelled) return;
        console.error('설문 문항 조회 실패:', error);
        setQuestionsError(error.message || '설문 문항을 불러오지 못했습니다.');
      });
    return () => {
      cancelled = true;
    };
  }, [isModalOpen, questions.length]);

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

  // 모달 저장: PATCH /strategies/me/profiles
  const handleSaveModal = async () => {
    if (!isModalComplete || submittingProfile) return;
    setSubmittingProfile(true);
    try {
      const answersPayload = questions.map((q) => ({
        questionId: q.questionId,
        optionId: modalAnswers[q.questionId],
      }));
      const result = await updateProfile({
        answers: answersPayload,
        freeText: modalPrinciple || undefined,
      });
      setProfile({
        riskLevel: result?.riskLevel ?? null,
        profileSummary: result?.profileSummary ?? '',
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

  // 룰셋 저장: PUT /strategies/me/rules
  const handleSaveAll = async () => {
    if (savingRules) return;

    const stopLossRate = Math.abs(Number(rules.stopLoss));
    const takeProfitRate = Number(rules.takeProfit);
    const maxDailyOrderCount = Number(rules.maxDailyOrders);
    const maxDailyLossAmount = Number(rules.maxLossLimit);

    if (
      !Number.isFinite(stopLossRate) || stopLossRate < 1 ||
      !Number.isFinite(takeProfitRate) || takeProfitRate < 1 ||
      !Number.isFinite(maxDailyOrderCount) || maxDailyOrderCount < 1 ||
      !Number.isFinite(maxDailyLossAmount) || maxDailyLossAmount < 1
    ) {
      alert('모든 룰셋 값은 1 이상의 숫자여야 합니다.');
      return;
    }

    setSavingRules(true);
    try {
      const result = await updateRules({
        stopLossRate,
        takeProfitRate,
        maxDailyOrderCount,
        maxDailyLossAmount,
        version: ruleVersion,
      });
      // 응답으로 받은 값으로 화면 동기화. 손절은 음수 표기로 유지.
      setRules({
        takeProfit: result?.takeProfitRate ?? takeProfitRate,
        stopLoss: -(result?.stopLossRate ?? stopLossRate),
        maxDailyOrders: result?.maxDailyOrderCount ?? maxDailyOrderCount,
        maxLossLimit: result?.maxDailyLossAmount ?? maxDailyLossAmount,
      });
      setRuleVersion(result?.version ?? ruleVersion + 1);
      alert('변경사항이 성공적으로 저장되었습니다.');
    } catch (error) {
      console.error('룰셋 저장 실패:', error);
      alert(error.message || '룰셋 저장 중 오류가 발생했습니다.');
    } finally {
      setSavingRules(false);
    }
  };

  const levelLabel = profile.riskLevel ? RISK_LEVEL_LABEL[profile.riskLevel] ?? profile.riskLevel : '미진단';
  const levelColor = profile.riskLevel ? RISK_LEVEL_COLOR[profile.riskLevel] ?? '#888' : '#888';

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
            <span className="profile-value" style={{ color: levelColor }}>{levelLabel}</span>
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

      {/* 4. 투자 성향 재진단 모달: 서버에서 받은 9개 문항 동적 렌더링 */}
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
                  <h3>{idx + 1}. {q.question}</h3>
                  <div className="options-grid">
                    {q.options.map((opt) => (
                      <button
                        key={opt.optionId}
                        className={`option-btn ${modalAnswers[q.questionId] === opt.optionId ? 'selected' : ''}`}
                        onClick={() => handleSelectOption(q.questionId, opt.optionId)}
                      >
                        {opt.label}
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
