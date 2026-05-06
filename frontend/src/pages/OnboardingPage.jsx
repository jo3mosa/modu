import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, ArrowRight, ArrowLeft } from 'lucide-react';
import './OnboardingPage.css';

export default function OnboardingPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  // 최종 제출 시 한 번에 모아서 백엔드로 전송
  const [survey, setSurvey] = useState({
    horizon: '',
    goal: '',
    risk: '',
  });
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

  const nextStep = () => setStep((prev) => Math.min(prev + 1, 4));
  const prevStep = () => setStep((prev) => Math.max(prev - 1, 1));

  const handleComplete = async () => {
    console.log("최종 데이터:", { survey, principle, rules, apiKeys });

    /*
    // API 연동 시 주석 해제 필요 !!
    try {
      const token = sessionStorage.getItem('tempToken'); // 로그인 시 받은 임시 토큰

      // 1. 투자 성향 + 룰셋 저장 API
      const profileResponse = await fetch('/api/v1/strategies/me/profiles', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({
          horizon: survey.horizon,
          goal: survey.goal,
          riskTolerance: survey.risk,
          principle: principle,
          takeProfit: rules.takeProfit,
          stopLoss: rules.stopLoss,
          positionSize: rules.positionSize
        })
      });

      if (!profileResponse.ok) throw new Error('성향 저장에 실패했습니다.');

      // 2. 한국투자증권 연동 API
      const kisResponse = await fetch('/api/v1/users/me/kis-keys', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({
          appKey: apiKeys.appKey,
          appSecret: apiKeys.appSecret
        })
      });

      if (!kisResponse.ok) throw new Error('계좌 연동에 실패했습니다.');

      // 3. 완료 시 메인 페이지로 이동
      alert('설정이 완료되었습니다! 매매를 시작해 보세요.');
      navigate('/home');
      return;
    } catch (error) {
      console.error(error);
      alert('오류가 발생했습니다: ' + error.message);
      return;
    }
    */

    // 임시 -> 클릭 시 메인으로 이동
    navigate('/home');
  };

  return (
    <div className="onboarding-container">
      {/* 진행도 표시 */}
      <div className="progress-bar">
        <div className="progress-fill" style={{ width: `${(step / 4) * 100}%` }} />
      </div>

      <div className="onboarding-content fade-in-slide" key={step}>
        {step === 1 && (
          <Step1Survey survey={survey} setSurvey={setSurvey} nextStep={nextStep} />
        )}
        {step === 2 && (
          <Step2Principle principle={principle} setPrinciple={setPrinciple} nextStep={nextStep} prevStep={prevStep} />
        )}
        {step === 3 && (
          <Step3Rules rules={rules} setRules={setRules} nextStep={nextStep} prevStep={prevStep} />
        )}
        {step === 4 && (
          <Step4ApiKeys apiKeys={apiKeys} setApiKeys={setApiKeys} handleComplete={handleComplete} prevStep={prevStep} />
        )}
      </div>
    </div>
  );
}

// 1단계 -> 객관식 투자 성향 설문
function Step1Survey({ survey, setSurvey, nextStep }) {
  const isComplete = survey.horizon && survey.goal && survey.risk;

  const handleSelect = (field, value) => {
    setSurvey(prev => ({ ...prev, [field]: value }));
  };

  return (
    <div className="step-wrapper">
      <h2 className="step-title">투자 성향 분석</h2>
      <p className="step-subtitle">정확한 AI 맞춤 분석을 위해 몇 가지 질문에 답해주세요!</p>

      <div className="survey-group">
        <h3>1. 투자 기간은 어느 정도로 생각하시나요?</h3>
        <div className="options-grid">
          {['단기 (1년 미만)', '중기 (1~3년)', '장기 (3년 이상)'].map(opt => (
            <button key={opt} className={`option-btn ${survey.horizon === opt ? 'selected' : ''}`} onClick={() => handleSelect('horizon', opt)}>
              {opt}
            </button>
          ))}
        </div>
      </div>

      <div className="survey-group">
        <h3>2. 주요 투자 목표는 무엇인가요?</h3>
        <div className="options-grid">
          {['단기 수익 실현', '장기 자산 증식', '노후/연금 준비'].map(opt => (
            <button key={opt} className={`option-btn ${survey.goal === opt ? 'selected' : ''}`} onClick={() => handleSelect('goal', opt)}>
              {opt}
            </button>
          ))}
        </div>
      </div>

      <div className="survey-group">
        <h3>3. 어느 정도의 위험을 감수하실 수 있나요?</h3>
        <div className="options-grid">
          {['안정 추구 (원금 보존)', '위험 중립 (적당한 변동성)', '적극 투자 (고위험 고수익)'].map(opt => (
            <button key={opt} className={`option-btn ${survey.risk === opt ? 'selected' : ''}`} onClick={() => handleSelect('risk', opt)}>
              {opt}
            </button>
          ))}
        </div>
      </div>

      <div className="step-actions">
        <button className="nav-btn primary" disabled={!isComplete} onClick={nextStep}>
          다음 <ArrowRight size={18} />
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

// 3단계 -> AI 결과 요약 + 룰셋 설정
function Step3Rules({ rules, setRules, nextStep, prevStep }) {
  return (
    <div className="step-wrapper">
      <h2 className="step-title">분석 결과 · 룰셋 설정</h2>
      <p className="step-subtitle">AI가 분석한 성향을 바탕으로 안전 장치를 설정합니다.</p>

      <div className="ai-summary-card">
        <h3>분석 완료!</h3>
        <p>공격적인 성향의 투자자입니다. 변동성 기반 매매 전략 수립이 적합합니다!</p>
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
function Step4ApiKeys({ apiKeys, setApiKeys, handleComplete, prevStep }) {
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
        <button className="nav-btn submit" disabled={!isComplete} onClick={handleComplete}>
          완료하고 시작하기
        </button>
      </div>
    </div>
  );
}