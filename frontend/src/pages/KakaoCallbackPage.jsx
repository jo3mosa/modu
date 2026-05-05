import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

export default function KakaoCallbackPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const code = searchParams.get('code');
  const [statusText, setStatusText] = useState('카카오 로그인 연동 중입니다...');

  useEffect(() => {
    if (!code) {
      setStatusText('잘못된 접근입니다. 인가 코드가 없습니다.');
      setTimeout(() => navigate('/login'), 2000);
      return;
    }

    const fetchSocialLogin = async () => {
      try {
        const response = await fetch('/api/v1/auth/social/kakao', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ code }),
        });

        const data = await response.json();

        // success -> true 반환
        if (response.ok && data.success) {
          // data.onboarding 구조: { isSurveyCompleted: true, isRuleSetCompleted: false }
          const { isSurveyCompleted, isRuleSetCompleted } = data.data.onboarding;

          if (isSurveyCompleted && isRuleSetCompleted) {
            navigate('/home'); // 둘 다 true -> 메인으로 이동
          } else {
            navigate('/onboarding'); // 하나라도 false -> 온보딩
          }
        } else {
          setStatusText(`로그인 실패: ${data.message || '알 수 없는 에러'}`);
          setTimeout(() => navigate('/login'), 2000);
        }
      } catch (error) {
        console.error('소셜 로그인 처리 중 에러 발생:', error);
        setStatusText('네트워크 에러가 발생했습니다.');
        setTimeout(() => navigate('/login'), 2000);
      }
    };

    fetchSocialLogin();
  }, [code, navigate]);

  return (
    <div style={{
      height: '100vh',
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      color: '#fff',
      flexDirection: 'column',
      gap: '1rem',
      fontFamily: "'Pretendard', sans-serif"
    }}>
      <h2>{statusText}</h2>
      <p style={{ color: '#888' }}>잠시만 기다려 주세요.</p>
    </div>
  );
}
