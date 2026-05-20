import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowLeft } from 'lucide-react';
import { getProfile, getProfileQuestions, updateProfile, updateRules } from '../api/strategy';
import { toast } from 'sonner';
import { registerKisKey } from '../api/user';
import './OnboardingPage.css';

const RISK_LEVEL_LABEL = {
  STABLE: '안정형',
  STABLE_SEEKING: '안정추구형',
  RISK_NEUTRAL: '위험중립형',
  ACTIVE: '적극투자형',
  AGGRESSIVE: '공격투자형',
};

const STEP_META = [
  { num: 1, label: '투자 성향' },
  { num: 2, label: '룰셋 설정' },
  { num: 3, label: '계좌 연동' },
];

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


  const [rules, setRules] = useState({
    takeProfit: '',
    stopLoss: '',
    positionSize: 'fixed',
  });
  const [apiKeys, setApiKeys] = useState({
    appKey: '',
    appSecret: '',
    htsId: '',
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

  const nextStep = () => setStep((prev) => Math.min(prev + 1, 3));
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

      // 기존 프로필이 있는지 먼저 확인 (version 확보용)
      let currentVersion = 0;
      try {
        const existing = await getProfile();
        currentVersion = existing.version;
      } catch (e) {
        // 404면 신규 등록이므로 0 유지
      }

      const result = await updateProfile({ 
        answers: answersPayload,
        version: currentVersion 
      });
      setProfileResult(result);
      nextStep();
    } catch (error) {
      console.error('투자 성향 저장 실패:', error);
      toast.error(error.message || '투자 성향 저장 중 오류가 발생했습니다. 다시 시도해 주세요.');
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
      toast.error('익절/손절 기준은 1 이상의 숫자로 입력해 주세요.');
      return;
    }

    setSubmittingComplete(true);
    try {
      // 룰셋 저장: 일일 한도는 사용자 입력이 없으므로 기본값. 추후 마이페이지에서 조정.
      await updateRules({
        stopLossRate,
        takeProfitRate,
        maxDailyOrderCount: 10,
        maxDailyLossAmount: 500000,
        version: 0,
      });

      // 3) KIS 키 등록 (appKey, appSecret, htsId, accountNo 모두 있을 때만)
      if (apiKeys.appKey && apiKeys.appSecret && apiKeys.htsId && apiKeys.accountNo) {
        try {
          await registerKisKey({
            appKey: apiKeys.appKey,
            appSecret: apiKeys.appSecret,
            htsId: apiKeys.htsId,
            accountNo: apiKeys.accountNo,
            isRealAccount: true,
          });
        } catch (kisError) {
          console.warn('KIS 키 등록 실패:', kisError);
          toast.error('KIS API 키 등록에 실패했습니다', {
            description: '마이페이지에서 다시 등록해 주세요.',
          });
        }
      }

      navigate('/home');
    } catch (error) {
      console.error('룰셋 저장 실패:', error);
      toast.error(error.message || '설정 저장 중 오류가 발생했습니다.');
    } finally {
      setSubmittingComplete(false);
    }
  };

  return (
    <div className="onboarding-container">
      {/* 진행도 표시 — 단계 번호/라벨 + 채워지는 바 */}
      <div className="progress-header">
        <ol className="progress-steps">
          {STEP_META.map((meta) => {
            const isCompleted = step > meta.num;
            const isCurrent = step === meta.num;
            return (
              <li
                key={meta.num}
                className={`progress-step${isCurrent ? ' is-current' : ''}${isCompleted ? ' is-completed' : ''}`}
              >
                <span className="progress-step-num">{meta.num}</span>
                <span className="progress-step-label">{meta.label}</span>
              </li>
            );
          })}
        </ol>
        <div className="progress-bar">
          <div className="progress-fill" style={{ width: `${(step / 3) * 100}%` }} />
        </div>
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
          <Step2Rules
            rules={rules}
            setRules={setRules}
            profileResult={profileResult}
            nextStep={nextStep}
            prevStep={prevStep}
          />
        )}
        {step === 3 && (
          <Step3ApiKeys
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
  const unansweredCount = questions.filter((q) => answers[q.questionId] == null).length;
  const isComplete = questions.length > 0 && unansweredCount === 0;

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

      {questions.map((q, idx) => {
        const isAnswered = answers[q.questionId] != null;
        return (
          <div
            key={q.questionId}
            className={`survey-group${!isAnswered ? ' needs-answer' : ''}`}
          >
            <h3>
              {idx + 1}. {q.question}
              {!isAnswered && <span className="survey-needs-badge">답변 필요</span>}
            </h3>
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
        );
      })}

      <div className="step-actions">
        {!isComplete && questions.length > 0 && (
          <span className="step-hint">
            {unansweredCount}문항 더 답변하면 다음으로 진행할 수 있어요
          </span>
        )}
        <button className="nav-btn primary" disabled={!isComplete || submitting} onClick={onSubmit}>
          {submitting ? '저장 중…' : '다음'} <ArrowRight size={18} />
        </button>
      </div>
    </div>
  );
}


// 2단계 -> 서버 산정 결과(riskLevel, profileSummary) 표시 + 룰셋 설정
function Step2Rules({ rules, setRules, profileResult, nextStep, prevStep }) {
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

// 3단계 -> 계좌 연동
function Step3ApiKeys({ apiKeys, setApiKeys, handleComplete, prevStep, submitting }) {
  const isComplete = apiKeys.appKey && apiKeys.appSecret && apiKeys.htsId && apiKeys.accountNo;

  return (
    <div className="step-wrapper">
      <h2 className="step-title">계좌 연동</h2>
      <p className="step-subtitle">마지막입니다! 실제 자동매매를 위해 한국투자증권 API 키를 연결해주세요!</p>

      <p className="api-form-helper">
        Open API 키는{' '}
        <a
          href="https://apiportal.koreainvestment.com/intro"
          target="_blank"
          rel="noopener noreferrer"
          className="api-form-helper-link"
        >
          한국투자증권 개발자센터
        </a>
        에서 발급할 수 있어요. HTS ID 는 한투 HTS/MTS 접속에 쓰는 일반 로그인 아이디입니다.
      </p>

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
          <label>HTS ID (한투 로그인 ID)</label>
          <input
            type="text"
            value={apiKeys.htsId}
            onChange={(e) => setApiKeys({ ...apiKeys, htsId: e.target.value })}
            placeholder="예: myhts01 — 한투 HTS/MTS 접속 ID"
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
