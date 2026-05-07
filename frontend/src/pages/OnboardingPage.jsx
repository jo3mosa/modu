import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowLeft } from 'lucide-react';
// 백엔드 strategies 컨트롤러 미구현 상태이므로 일시적으로 mock 동작.
// 백엔드 PR 머지 후 아래 import + 호출 블록 주석을 해제하면 즉시 실 API로 전환된다.
// import { getProfileQuestions, updateProfile, updateRules } from '../api/strategy';
import './OnboardingPage.css';

const RISK_GRADE_LABEL = {
  STABLE: '안정형',
  STABLE_SEEKING: '안정추구형',
  RISK_NEUTRAL: '위험중립형',
  ACTIVE: '적극투자형',
  AGGRESSIVE: '공격투자형',
};

// MOCK 설문 문항 (백엔드 strategies API 머지 후 서버 조회로 대체)
const MOCK_QUESTIONS = [
  {
    questionId: 'INVESTMENT_PERIOD',
    text: '투자 기간은 어느 정도로 생각하시나요?',
    options: [
      { optionId: 1, text: '단기 (1년 미만)' },
      { optionId: 2, text: '중기 (1~3년)' },
      { optionId: 3, text: '장기 (3년 이상)' },
    ],
  },
  {
    questionId: 'INVESTMENT_GOAL',
    text: '주요 투자 목표는 무엇인가요?',
    options: [
      { optionId: 1, text: '단기 수익 실현' },
      { optionId: 2, text: '장기 자산 증식' },
      { optionId: 3, text: '노후/연금 준비' },
    ],
  },
  {
    questionId: 'RISK_TOLERANCE',
    text: '어느 정도의 위험을 감수하실 수 있나요?',
    options: [
      { optionId: 1, text: '안정 추구 (원금 보존)' },
      { optionId: 2, text: '위험 중립 (적당한 변동성)' },
      { optionId: 3, text: '적극 투자 (고위험 고수익)' },
    ],
  },
];

// MOCK 분석 결과 (백엔드 strategies API 머지 후 PATCH 응답으로 대체)
const MOCK_PROFILE_RESULT = {
  riskGrade: 'ACTIVE',
  profileSummary: '공격적인 성향의 투자자입니다. 변동성 기반 매매 전략 수립이 적합합니다!',
};

export default function OnboardingPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  // 설문 문항 (백엔드 머지 후 서버 조회 결과로 교체)
  const questions = MOCK_QUESTIONS;
  const questionsError = null;

  // 답변: { [questionId]: optionId }
  const [answers, setAnswers] = useState({});

  // 투자 성향 분석 결과
  const [profileResult, setProfileResult] = useState(null);
  const [submittingProfile, setSubmittingProfile] = useState(false);

  const [principle, setPrinciple] = useState('');
  const [rules, setRules] = useState({
    takeProfit: '',
    stopLoss: '',
    positionSize: 'fixed',
  });
  const [apiKeys, setApiKeys] = useState({
    appKey: '',
    appSecret: '',
  });
  const [submittingComplete, setSubmittingComplete] = useState(false);

  // ── TODO: 백엔드 strategies API 머지 후 아래 블록 해제 (설문 문항 서버 조회) ──
  // const [questions, setQuestions] = useState([]);
  // const [questionsError, setQuestionsError] = useState(null);
  // useEffect(() => {
  //   let cancelled = false;
  //   getProfileQuestions()
  //     .then((data) => { if (!cancelled) setQuestions(data?.questions ?? []); })
  //     .catch((error) => {
  //       if (cancelled) return;
  //       console.error('설문 문항 조회 실패:', error);
  //       setQuestionsError(error.message || '설문 문항을 불러오지 못했습니다.');
  //     });
  //   return () => { cancelled = true; };
  // }, []);
  // ──────────────────────────────────────────────────────────────────────────

  const nextStep = () => setStep((prev) => Math.min(prev + 1, 4));
  const prevStep = () => setStep((prev) => Math.max(prev - 1, 1));

  // Step1 → Step2 진입 시 투자 성향 산정
  const handleSurveySubmit = async () => {
    if (submittingProfile) return;
    setSubmittingProfile(true);
    try {
      // ── TODO: 백엔드 strategies API 머지 후 아래 주석 블록 해제 ────────────
      // const answersPayload = questions.map((q) => ({
      //   questionId: q.questionId,
      //   optionId: answers[q.questionId],
      // }));
      // const result = await updateProfile(answersPayload);
      // setProfileResult(result);
      // ─────────────────────────────────────────────────────────────────────
      setProfileResult(MOCK_PROFILE_RESULT);
      nextStep();
    } catch (error) {
      console.error('투자 성향 저장 실패:', error);
      alert(error.message || '투자 성향 저장 중 오류가 발생했습니다.');
    } finally {
      setSubmittingProfile(false);
    }
  };

  const handleComplete = async () => {
    if (submittingComplete) return;
    setSubmittingComplete(true);
    try {
      // ── TODO: 백엔드 strategies API 머지 후 아래 주석 블록 해제 ────────────
      // await updateRules({
      //   principle,
      //   takeProfit: Number(rules.takeProfit),
      //   stopLoss: Number(rules.stopLoss),
      //   positionSize: rules.positionSize,
      // });
      // ─────────────────────────────────────────────────────────────────────
      // TODO: KIS 키 등록은 별도 API 연동 후 처리 (POST /users/me/kis-keys)
      navigate('/mypage');
    } catch (error) {
      console.error('룰셋 저장 실패:', error);
      alert(error.message || '설정 저장 중 오류가 발생했습니다.');
    } finally {
      setSubmittingComplete(false);
    }
  };

  return (
    <div className="onboarding-container">
      {/* 진행도 표시 */}
      <div className="progress-bar">
        <div className="progress-fill" style={{ width: `${(step / 4) * 100}%` }} />
      </div>

      <div className="onboarding-content fade-in-slide" key={step}>
        {step === 1 && (
          <Step1Survey
            questions={questions}
            questionsError={questionsError}
            answers={answers}
            setAnswers={setAnswers}
            onSubmit={handleSurveySubmit}
            submitting={submittingProfile}
          />
        )}
        {step === 2 && (
          <Step2Principle principle={principle} setPrinciple={setPrinciple} nextStep={nextStep} prevStep={prevStep} />
        )}
        {step === 3 && (
          <Step3Rules
            rules={rules}
            setRules={setRules}
            profileResult={profileResult}
            nextStep={nextStep}
            prevStep={prevStep}
          />
        )}
        {step === 4 && (
          <Step4ApiKeys
            apiKeys={apiKeys}
            setApiKeys={setApiKeys}
            handleComplete={handleComplete}
            prevStep={prevStep}
            submitting={submittingComplete}
          />
        )}
      </div>
    </div>
  );
}

// 1단계 -> 9개 객관식 문항 표시 + 답변 수집 (현재 mock)
function Step1Survey({ questions, questionsError, answers, setAnswers, onSubmit, submitting }) {
  const isComplete =
    questions.length > 0 && questions.every((q) => answers[q.questionId] != null);

  const handleSelect = (questionId, optionId) => {
    setAnswers((prev) => ({ ...prev, [questionId]: optionId }));
  };

  return (
    <div className="step-wrapper">
      <h2 className="step-title">투자 성향 분석</h2>
      <p className="step-subtitle">정확한 AI 맞춤 분석을 위해 몇 가지 질문에 답해주세요!</p>

      {questionsError && <p className="error-text">{questionsError}</p>}

      {!questionsError && questions.length === 0 && (
        <p className="loading-text">설문 문항을 불러오는 중입니다…</p>
      )}

      {questions.map((q, idx) => (
        <div key={q.questionId} className="survey-group">
          <h3>{idx + 1}. {q.text}</h3>
          <div className="options-grid">
            {q.options.map((opt) => (
              <button
                key={opt.optionId}
                className={`option-btn ${answers[q.questionId] === opt.optionId ? 'selected' : ''}`}
                onClick={() => handleSelect(q.questionId, opt.optionId)}
              >
                {opt.text}
              </button>
            ))}
          </div>
        </div>
      ))}

      <div className="step-actions">
        <button className="nav-btn primary" disabled={!isComplete || submitting} onClick={onSubmit}>
          {submitting ? '저장 중…' : '다음'} <ArrowRight size={18} />
        </button>
      </div>
    </div>
  );
}

// 2단계 -> 주관식 투자 원칙
function Step2Principle({ principle, setPrinciple, nextStep, prevStep }) {
  return (
    <div className="step-wrapper">
      <h2 className="step-title">나만의 투자 원칙</h2>
      <p className="step-subtitle">객관식으로 담지 못한 본인만의 투자 원칙을 자유롭게 적어주세요.</p>

      <textarea
        className="principle-textarea"
        placeholder="예: 추격 매수를 하지 않습니다. 하락 시 분할 매수합니다."
        value={principle}
        onChange={(e) => setPrinciple(e.target.value)}
      />

      <div className="step-actions split">
        <button className="nav-btn secondary" onClick={prevStep}>
          <ArrowLeft size={18} /> 이전
        </button>
        <button className="nav-btn primary" onClick={nextStep}>
          다음 <ArrowRight size={18} />
        </button>
      </div>
    </div>
  );
}

// 3단계 -> 분석 결과 표시 + 룰셋 설정
function Step3Rules({ rules, setRules, profileResult, nextStep, prevStep }) {
  const gradeLabel = profileResult?.riskGrade
    ? RISK_GRADE_LABEL[profileResult.riskGrade] ?? profileResult.riskGrade
    : null;
  const summary = profileResult?.profileSummary;

  return (
    <div className="step-wrapper">
      <h2 className="step-title">분석 결과 · 룰셋 설정</h2>
      <p className="step-subtitle">서버가 산정한 성향을 바탕으로 안전 장치를 설정합니다.</p>

      <div className="ai-summary-card">
        <h3>{gradeLabel ? `${gradeLabel} 투자자` : '분석 완료!'}</h3>
        <p>{summary || '투자 성향이 산정되었습니다.'}</p>
      </div>

      <div className="rule-group">
        <h3>목표 수익 · 허용 손실</h3>
        <div className="input-row">
          <div className="input-box">
            <label>익절 기준 (%)</label>
            <input type="number" value={rules.takeProfit} onChange={(e) => setRules({ ...rules, takeProfit: e.target.value })} placeholder="예: 5" />
          </div>
          <div className="input-box">
            <label>손절 기준 (%)</label>
            <input type="number" value={rules.stopLoss} onChange={(e) => setRules({ ...rules, stopLoss: e.target.value })} placeholder="예: -3" />
          </div>
        </div>
      </div>

      <div className="rule-group">
        <h3>포지션 크기 제한 (비중 조절 전략)</h3>
        <div className="options-grid sizing">
          {[
            { id: 'fixed', label: '고정 비율' },
            { id: 'kelly', label: '승률 기반' },
            { id: 'variable', label: '유동적 비중' }
          ].map(opt => (
            <button
              key={opt.id}
              className={`option-btn ${rules.positionSize === opt.id ? 'selected' : ''}`}
              onClick={() => setRules({ ...rules, positionSize: opt.id })}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      <div className="step-actions split">
        <button className="nav-btn secondary" onClick={prevStep}>
          이전
        </button>
        <button className="nav-btn primary" onClick={nextStep}>
          다음
        </button>
      </div>
    </div>
  );
}

// 4단계 -> 계좌 연동
function Step4ApiKeys({ apiKeys, setApiKeys, handleComplete, prevStep, submitting }) {
  const isComplete = apiKeys.appKey && apiKeys.appSecret;

  return (
    <div className="step-wrapper">
      <h2 className="step-title">계좌 연동</h2>
      <p className="step-subtitle">마지막입니다! 실제 자동매매를 위해 한국투자증권 API 키를 연결해주세요!</p>

      <div className="api-form">
        <div className="input-box">
          <label>App Key (앱 키)</label>
          <input
            type="text"
            value={apiKeys.appKey}
            onChange={(e) => setApiKeys({ ...apiKeys, appKey: e.target.value })}
            placeholder="한국투자증권 App Key를 입력하세요"
          />
        </div>
        <div className="input-box">
          <label>App Secret (앱 시크릿)</label>
          <input
            type="password"
            value={apiKeys.appSecret}
            onChange={(e) => setApiKeys({ ...apiKeys, appSecret: e.target.value })}
            placeholder="한국투자증권 App Secret을 입력하세요"
          />
        </div>
      </div>

      <div className="step-actions split">
        <button className="nav-btn secondary" onClick={prevStep}>
          이전
        </button>
        <button className="nav-btn submit" disabled={!isComplete || submitting} onClick={handleComplete}>
          {submitting ? '저장 중…' : '완료하고 시작하기'}
        </button>
      </div>
    </div>
  );
}
