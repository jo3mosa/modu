import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, AlertCircle } from 'lucide-react';
import { socialLogin } from '../api/auth';
import './KakaoCallbackPage.css';

export default function KakaoCallbackPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const code = searchParams.get('code');

  const [status, setStatus] = useState('loading'); // 'loading' | 'error'
  const [errorMsg, setErrorMsg] = useState('');

  // StrictMode 이중 마운트 시 인가 코드를 두 번 사용해 두 번째 요청이 실패하는 케이스를 차단
  const requestedRef = useRef(false);

  useEffect(() => {
    if (requestedRef.current) return;
    requestedRef.current = true;

    if (!code) {
      setStatus('error');
      setErrorMsg('잘못된 접근입니다. 인가 코드가 없습니다.');
      return;
    }

    (async () => {
      try {
        const data = await socialLogin(code);
        const { isSurveyCompleted, isRuleSetCompleted } = data.onboarding;
        if (isSurveyCompleted && isRuleSetCompleted) {
          navigate('/home');
        } else {
          navigate('/onboarding');
        }
      } catch (error) {
        console.error('소셜 로그인 처리 중 에러 발생:', error);
        setStatus('error');
        setErrorMsg(error.message || '네트워크 에러가 발생했습니다. 잠시 후 다시 시도해주세요.');
      }
    })();
  }, [code, navigate]);

  return (
    <div className="kakao-callback-container">
      <div className="kakao-callback-card">
        {status === 'loading' ? (
          <>
            <Loader2 className="kakao-callback-spinner" size={40} />
            <h2 className="kakao-callback-title">카카오 로그인 연동 중</h2>
            <p className="kakao-callback-desc">잠시만 기다려 주세요…</p>
          </>
        ) : (
          <>
            <AlertCircle className="kakao-callback-icon-error" size={40} />
            <h2 className="kakao-callback-title">로그인에 실패했어요</h2>
            <p className="kakao-callback-desc">{errorMsg}</p>
            <button
              type="button"
              className="kakao-callback-btn"
              onClick={() => navigate('/login')}
            >
              로그인 화면으로
            </button>
          </>
        )}
      </div>
    </div>
  );
}
