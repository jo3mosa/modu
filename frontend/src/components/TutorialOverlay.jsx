import { useState, useEffect } from 'react';
import { ArrowRight, ArrowLeft, Check, X, HandMetal, BarChart2, Zap, FileText } from 'lucide-react';
import './TutorialOverlay.css';

const TUTORIAL_STEPS = [
  {
    id: 1,
    title: '환영합니다!',
    desc: '모두와 함께 투자하는 즐거움을 느껴보세요.\n본격적인 매매를 시작하기 전, 주요 기능들을 훑어보세요.',
    icon: <HandMetal size={48} color="#84cc16" />,
  },
  {
    id: 2,
    title: '총 자산 요약',
    desc: '실시간 총 자산과 손익, 그리고 보유 중인 종목을\n 대시보드에서 한눈에 파악할 수 있습니다.',
    icon: <BarChart2 size={48} color="#3b82f6" />,
  },
  {
    id: 3,
    title: 'AI 자동매매 제어',
    desc: '트레이딩 룸 메뉴에서\n설정한 AI 매매 엔진을 언제든 켜고 끌 수 있습니다.',
    icon: <Zap size={48} color="#eab308" />,
  },
  {
    id: 4,
    title: '리포트',
    desc: 'AI가 시장을 분석하고, 나의 투자 성과를 요약해 줍니다.\n매일 리포트를 확인해 보세요!',
    icon: <FileText size={48} color="#a855f7" />,
  }
];

export default function TutorialOverlay({ onClose }) {
  const [step, setStep] = useState(1);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    setIsVisible(true);
  }, []);

  const handleNext = () => setStep((prev) => Math.min(prev + 1, TUTORIAL_STEPS.length));
  const handlePrev = () => setStep((prev) => Math.max(prev - 1, 1));

  const handleClose = () => {
    setIsVisible(false);
    setTimeout(() => {
      onClose();
    }, 300);
  };

  const currentStepData = TUTORIAL_STEPS[step - 1];

  return (
    <div className={`tutorial-backdrop ${isVisible ? 'visible' : ''}`}>
      <div className="tutorial-modal">
        <button className="close-btn" onClick={handleClose}>
          <X size={24} />
        </button>

        <div className="tutorial-header">
          <div className="step-indicator">
            {TUTORIAL_STEPS.map((s) => (
              <div key={s.id} className={`step-dot ${s.id === step ? 'active' : ''}`} />
            ))}
          </div>
        </div>

        <div className="tutorial-body">
          <h2 className="tutorial-title">{currentStepData.title}</h2>
          <p className="tutorial-desc">
            {currentStepData.desc.split('\n').map((line, idx) => (
              <span key={idx}>
                {line}
                <br />
              </span>
            ))}
          </p>
        </div>

        <div className="tutorial-actions">
          {step > 1 ? (
            <button className="nav-btn prev" onClick={handlePrev}>
              이전
            </button>
          ) : (
            <button className="nav-btn skip" onClick={handleClose}>
              건너뛰기
            </button>
          )}

          {step < TUTORIAL_STEPS.length ? (
            <button className="nav-btn next" onClick={handleNext}>
              다음
            </button>
          ) : (
            <button className="nav-btn finish" onClick={handleClose}>
              매매 시작하기
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
