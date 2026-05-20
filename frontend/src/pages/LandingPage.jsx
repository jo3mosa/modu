import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Spline from '@splinetool/react-spline';
import { ArrowRight } from 'lucide-react';
import tradingImg from '../assets/trading.png';
import './LandingPage.css';

export default function LandingPage() {
  const navigate = useNavigate();
  const observerRef = useRef(null);
  // Spline 3D 씬 로드 완료 여부 — 로드 전에는 어두운 그라데이션 폴백 노출
  const [isSplineLoaded, setIsSplineLoaded] = useState(false);

  useEffect(() => {
    observerRef.current = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('fade-in-visible');
        }
      });
    }, { threshold: 0.15 });

    const elements = document.querySelectorAll('.fade-in-section');
    elements.forEach((el) => observerRef.current.observe(el));

    return () => {
      if (observerRef.current) observerRef.current.disconnect();
    };
  }, []);

  return (
    <div className="landing-container">
      <section className="hero-section">
        {/* Spline 로딩 동안 배경이 휑하지 않도록 그라데이션 폴백을 깔아둔다 */}
        <div className={`spline-bg ${isSplineLoaded ? 'loaded' : ''}`}>
          <div className="spline-fallback" aria-hidden="true" />
          <Spline
            scene="https://prod.spline.design/ZPgpwWX110LhBG0e/scene.splinecode"
            onLoad={() => setIsSplineLoaded(true)}
          />
        </div>

        <div className="hero-content fade-in-section">
          <h1 className="hero-title">당신의 투자를<br />모두와 함께.</h1>
          <p className="hero-subtitle">
            Make Optimal Decision for You<br />
            나만의 에이전트를 고용해보세요.
          </p>
          {/* Hero CTA — 사용자가 첫 화면에서 바로 가입 동선으로 진입 가능 */}
          <div className="hero-cta-group">
            <button
              type="button"
              className="cta-button hero-cta-primary"
              onClick={() => navigate('/login')}
            >
              지금 시작하기 <ArrowRight size={18} />
            </button>
            <button
              type="button"
              className="hero-cta-secondary"
              onClick={() => {
                document.querySelector('.features-section')?.scrollIntoView({ behavior: 'smooth' });
              }}
            >
              기능 살펴보기
            </button>
          </div>
        </div>
      </section>

      {/* 2. 주요 기능 섹션 */}
      <section className="features-section">
        <h2 className="section-title fade-in-section">왜 <span className="brand-font">MODU</span>인가요?</h2>
        <div className="features-grid fade-in-section">
          <div className="feature-card">
            <h3 className="feature-title">나만의 투자 전략을 AI에게</h3>
            <p className="feature-desc">
              투자 성향 설문으로 위험 등급을 정의하고,<br />
              그에 맞는 전략을 AI 에이전트가 실행합니다.
            </p>
          </div>

          <div className="feature-card">
            <h3 className="feature-title">리스크 관리</h3>
            <p className="feature-desc">
              시장 급락 시 모든 포지션을<br />
              정리하고 자동매매를 멈추는 비상 정지 기능.
            </p>
          </div>

          <div className="feature-card">
            <h3 className="feature-title">실시간 대응</h3>
            <p className="feature-desc">
              한국투자증권 실시간 웹소켓 연동으로<br />
              틱 단위의 호가와 체결에 반응합니다.
            </p>
          </div>
        </div>
      </section>

      {/* 3. 서비스 미리보기 섹션 */}
      <section className="sneak-peek-section">
        <h2 className="section-title fade-in-section">모든 것을 한눈에.</h2>
        <div className="fade-in-section">
          <div className="mockup-wrapper">
            <img src={tradingImg} alt="MODU 대시보드 화면" className="mockup-image" />
          </div>
        </div>
      </section>

      {/* 4. 투자 유의 사항 (면책 조항) */}
      <section className="disclaimer-section">
        <h2 className="section-title fade-in-section">투자 유의 사항</h2>
        <div className="disclaimer-content fade-in-section">
          <p>
            MODU는 AI 및 데이터 기반의 투자 보조 도구를 제공하지만, <strong>투자의 최종 결정과 책임은 투자자 본인에게 있습니다.</strong><br />
            자동매매 및 AI 분석 결과는 시장 상황에 따라 변동될 수 있으며, 어떠한 경우에도 수익을 보장하지 않습니다.
          </p>
        </div>
      </section>

      {/* 5. 하단 가입 유도 섹션 */}
      <section className="bottom-cta-section">
        <h2 className="bottom-cta-title fade-in-section">모두와 함께<br />모든 순간을 기회로 만드세요.</h2>
        <button className="cta-button bottom-btn fade-in-section" onClick={() => navigate('/login')}>
          시작하기
        </button>
      </section>
    </div>
  );
}