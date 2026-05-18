import { useEffect } from 'react';
import './ConfirmDialog.css';

/**
 * 커스텀 확인 다이얼로그. window.confirm 대체.
 *
 * - open=false 이면 렌더링하지 않음
 * - ESC / dim 클릭 / 취소 → onCancel
 * - 확인 → onConfirm
 * - variant: 'default' | 'danger' (확인 버튼 색)
 *
 * 사용 예:
 *   const [confirmState, setConfirmState] = useState(null);
 *   const askCancel = (order) =>
 *     setConfirmState({
 *       title: '주문 취소',
 *       message: `주문(${order.orderId})을 취소하시겠습니까?`,
 *       confirmLabel: '취소',
 *       variant: 'danger',
 *       onConfirm: () => doCancel(order),
 *     });
 *
 *   <ConfirmDialog
 *     open={!!confirmState}
 *     {...(confirmState ?? {})}
 *     onClose={() => setConfirmState(null)}
 *   />
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  description,
  confirmLabel = '확인',
  cancelLabel = '취소',
  variant = 'default',
  onConfirm,
  onClose,
}) {
  // ESC 닫기
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e) => {
      if (e.key === 'Escape') onClose?.();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) onClose?.();
  };

  const handleConfirm = async () => {
    try {
      await onConfirm?.();
    } finally {
      onClose?.();
    }
  };

  return (
    <div className="confirm-backdrop" onClick={handleBackdropClick} role="dialog" aria-modal="true">
      <div className="confirm-dialog" role="document">
        {title && <h3 className="confirm-title">{title}</h3>}
        {message && <p className="confirm-message">{message}</p>}
        {description && <p className="confirm-description">{description}</p>}

        <div className="confirm-actions">
          <button type="button" className="confirm-btn confirm-btn-cancel" onClick={onClose}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`confirm-btn confirm-btn-primary ${variant === 'danger' ? 'is-danger' : ''}`}
            onClick={handleConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
