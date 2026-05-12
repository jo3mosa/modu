import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { socialLogin } from '../api/auth';

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
        // POST /api/v1/auth/social/kakao → { onboarding: { isSurveyCompleted, isRuleSetCompleted } }
        const data = await socialLogin(code);
        console.log('카카오 로그인 성공 - 온보딩 상태:', data.onboarding);
        const { isSurveyCompleted, isRuleSetCompleted } = data.onboarding;

        if (isSurveyCompleted && isRuleSetCompleted) {
          navigate('/home');
        } else {
          navigate('/onboarding');
        }
      } catch (error) {
        console.error('소셜 로그인 처리 중 에러 발생:', error);
        setStatusText(`로그인 실패: ${error.message || '네트워크 에러가 발생했습니다.'}`);
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
