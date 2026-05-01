import { useState, useEffect } from 'react';
import { Search } from 'lucide-react';
import TutorialOverlay from '../components/TutorialOverlay';

export default function DashboardPage() {
  const [showTutorial, setShowTutorial] = useState(false);

  useEffect(() => {
    // 연동 시 -> 최초 1회만
    // const isCompleted = localStorage.getItem('tutorialCompleted');
    // if (!isCompleted) setShowTutorial(true);

    // 현재는 무조건 뜨도록 설정 ~
    setShowTutorial(true);
  }, []);

  const handleCloseTutorial = () => {
    // localStorage.setItem('tutorialCompleted', 'true');
    setShowTutorial(false);
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>

      {showTutorial && <TutorialOverlay onClose={handleCloseTutorial} />}

      <h1 style={{ fontSize: '2rem', marginBottom: '2rem' }}>
        총 자산 <br />
        <span style={{ fontSize: '3rem', fontWeight: '800' }}>600,000원</span>
      </h1>

      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
        gap: '1.5rem',
        marginBottom: '3rem'
      }}>
        {/* 요약 -> 목업 기준 */}
        {[
          { title: '총 자산', value: '600,000원', sub: '+10.7%' },
          { title: '현금 잔액', value: '200,000원', sub: '+1.5%' },
          { title: '오늘의 손익', value: '30,000원', sub: '+5.0%' },
          { title: '내 관심 종목', value: 'TSLA', sub: '+3.0%' },
        ].map((item, idx) => (
          <div key={idx} style={{
            background: 'rgba(255,255,255,0.05)',
            padding: '1.5rem',
            borderRadius: '12px',
            border: '1px solid rgba(255,255,255,0.1)'
          }}>
            <h3 style={{ color: '#aaa', fontSize: '1rem', marginBottom: '1rem' }}>{item.title}</h3>
            <p style={{ fontSize: '1.5rem', fontWeight: 'bold', marginBottom: '0.5rem' }}>{item.value}</p>
            <span style={{ color: '#84cc16', fontSize: '0.9rem' }}>{item.sub}</span>
          </div>
        ))}
      </div>

      <h2 style={{ fontSize: '1.25rem', marginBottom: '1.5rem', color: '#ccc' }}>보유 종목</h2>
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
        gap: '1rem'
      }}>
        {/* 보유 종목 */}
        {[
          { title: 'NVDA', value: '200$', sub: '+3.0%' },
          { title: 'MSFT', value: '400$', sub: '+1.5%' },
          { title: 'TSLA', value: '390$', sub: '-3.5%', isDown: true },
          { title: 'AAPL', value: '260$', sub: '+3.0%' },
        ].map((item, idx) => (
          <div key={idx} style={{
            background: 'rgba(255,255,255,0.05)',
            padding: '1.5rem',
            borderRadius: '12px',
            border: '1px solid rgba(255,255,255,0.1)'
          }}>
            <h3 style={{ fontSize: '1.2rem', marginBottom: '1rem' }}>{item.title}</h3>
            <p style={{ fontSize: '1.2rem', fontWeight: 'bold', marginBottom: '0.5rem' }}>{item.value}</p>
            <span style={{ color: item.isDown ? '#ef4444' : '#84cc16', fontSize: '0.9rem' }}>{item.sub}</span>
          </div>
        ))}
      </div>

    </div>
  );
}