import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowLeft } from 'lucide-react';
import { getProfileQuestions, updateProfile, updateRules } from '../api/strategy';
import { registerKisKey } from '../api/user';
import './OnboardingPage.css';

const RISK_LEVEL_LABEL = {
  STABLE: '안정형',
  STABLE_SEEKING: '안정추구형',
  RISK_NEUTRAL: '위험중립형',
  ACTIVE: '적극투자형',
  AGGRESSIVE: '공격투자형',
};

export default function OnboardingPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  // 설문 문항 (서버 조회)
  const [questions, setQuestions] = useState([]);
  const [questionsError, setQuestionsError] = useState(null);

  // 답변: { [questionId]: optionId(string) }
  const [answers, setAnswers] = useState({});

  // PATCH /profiles 응답 (riskLevel, profileSummary 등)
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
    accountNo: '',
  });
  const [submittingComplete, setSubmittingComplete] = useState(false);

  useEffect(() => {
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
  }, []);

  const nextStep = () => setStep((prev) => Math.min(prev + 1, 4));
  const prevStep = () => setStep((prev) => Math.max(prev - 1, 1));

  // Step1 → Step2 진입 시 투자 성향을 서버에 PATCH 하고 응답을 보관한다.
  // freeText(매매 원칙)는 Step2에서 입력받으므로 이 단계에선 보내지 않는다.
  const handleSurveySubmit = async () => {
    if (submittingProfile) return;
    setSubmittingProfile(true);
    try {
      const answersPayload = questions.map((q) => ({
        questionId: q.questionId,
        optionId: answers[q.questionId],
      }));
      const result = await updateProfile({ answers: answersPayload });
      setProfileResult(result);
      nextStep();
    } catch (error) {
      console.error('투자 성향 저장 실패:', error);
      alert(error.message || '투자 성향 저장 중 오류가 발생했습니다. 다시 시도해 주세요.');
    } finally {
      setSubmittingProfile(false);
    }
  };

  const handleComplete = async () => {
    if (submittingComplete) return;

    // 백엔드 검증: 모든 값 @Min(1) 양수. stopLoss는 음수 입력이라 절대값.
    const stopLossRate = Math.abs(Number(rules.stopLoss));
    const takeProfitRate = Number(rules.takeProfit);

    if (
      !Number.isFinite(stopLossRate) || stopLossRate < 1 ||
      !Number.isFinite(takeProfitRate) || takeProfitRate < 1
    ) {
      alert('익절/손절 기준은 1 이상의 숫자로 입력해 주세요.');
      return;
    }

    setSubmittingComplete(true);
    try {
      // 1) 매매 원칙(자유 입력)은 PATCH /strategies/me/profiles의 freeText로 별도 저장
      if (principle?.trim()) {
        try {
          // 답변은 이전 단계(handleSurveySubmit)에서 이미 저장된 상태를 유지하기 위해
          // 동일 답변 + freeText만 함께 보내는 방식. 백엔드는 9개 답변을 항상 요구한다.
          const answersPayload = questions.map((q) => ({
            questionId: q.questionId,
            optionId: answers[q.questionId],
          }));
          await updateProfile({ answers: answersPayload, freeText: principle });
        } catch (error) {
          // 매매 원칙 저장 실패는 룰셋 저장을 막지 않는다 (UX 우선).
          console.warn('매매 원칙 저장 실패:', error);
        }
      }

      // 2) 룰셋 저장: 일일 한도는 사용자 입력이 없으므로 기본값. 추후 마이페이지에서 조정.
      await updateRules({
        stopLossRate,
        takeProfitRate,
        maxDailyOrderCount: 10,
        maxDailyLossAmount: 500000,
        version: 0,
      });

      // 3) KIS 키 등록 (appKey, appSecret, accountNo 모두 있을 때만)
      if (apiKeys.appKey && apiKeys.appSecret && apiKeys.accountNo) {
        try {
          await registerKisKey({
            appKey: apiKeys.appKey,
            appSecret: apiKeys.appSecret,
            accountNo: apiKeys.accountNo,
            isRealAccount: true,
          });
        } catch (kisError) {
          console.warn('KIS 키 등록 실패 (마이페이지에서 재등록 가능):', kisError);
          alert('KIS API 키 등록에 실패했습니다.\n마이페이지에서 다시 등록해 주세요.');
        }
      }

      navigate('/home');
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

// 1단계 -> 서버에서 받아온 9개 객관식 문항 표시 + 답변 수집
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
          <h3>{idx + 1}. {q.question}</h3>
          <div className="options-grid">
            {q.options.map((opt) => (
              <button
                key={opt.optionId}
                className={`option-btn ${answers[q.questionId] === opt.optionId ? 'selected' : ''}`}
                onClick={() => handleSelect(q.questionId, opt.optionId)}
              >
                {opt.label}
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

// 3단계 -> 서버 산정 결과(riskLevel, profileSummary) 표시 + 룰셋 설정
function Step3Rules({ rules, setRules, profileResult, nextStep, prevStep }) {
  const levelLabel = profileResult?.riskLevel
    ? RISK_LEVEL_LABEL[profileResult.riskLevel] ?? profileResult.riskLevel
    : null;
  const summary = profileResult?.profileSummary;

  return (
    <div className="step-wrapper">
      <h2 className="step-title">분석 결과 · 룰셋 설정</h2>
      <p className="step-subtitle">서버가 산정한 성향을 바탕으로 안전 장치를 설정합니다.</p>

      <div className="ai-summary-card">
        <h3>{levelLabel ? `${levelLabel} 투자자` : '분석 완료!'}</h3>
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
  const isComplete = apiKeys.appKey && apiKeys.appSecret && apiKeys.accountNo;

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
        <div className="input-box">
          <label>계좌번호 (계좌번호-상품코드)</label>
          <input
            type="text"
            value={apiKeys.accountNo}
            onChange={(e) => setApiKeys({ ...apiKeys, accountNo: e.target.value })}
            placeholder="예: 50012345-01"
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
