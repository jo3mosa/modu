import './InlineError.css';

/**
 * 영역별 데이터 로드 실패 표시 카드.
 * onRetry가 주어지면 재시도 버튼 노출.
 */
export default function InlineError({
  message = '데이터를 불러오지 못했습니다.',
  onRetry,
  compact = false,
}) {
  return (
    <div className={`inline-error ${compact ? 'inline-error--compact' : ''}`} role="alert">
      <span className="inline-error-message">{message}</span>
      {onRetry && (
        <button type="button" className="inline-error-retry" onClick={onRetry}>
          재시도
        </button>
      )}
    </div>
  );
}
