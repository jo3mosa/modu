import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { toast } from 'sonner';
import { approveDecision, rejectDecision } from '../api/aiAgent';
import { useNotifications } from '../hooks/useNotifications';
import { usePendingDecisions } from '../hooks/usePendingDecisions';
import './PendingDecisionsModal.css';

/**
 * AI 판단 승인 대기 모달. Bell 팝업의 "확인" 버튼으로 토글.
 *
 * - usePendingDecisions Context에서 pending + now(카운트다운 기준) 수신
 * - [승인] / [거부] 버튼은 disable 가드 (중복 호출 방지)
 * - 에러 응답 분기: 403 / 409 / 410은 사용자 친화 메시지 + 즉시 refresh
 * - ESC / dim 클릭 시 닫기
 */
export default function PendingDecisionsModal({ onClose }) {
  const { pending, now, refresh } = usePendingDecisions();
  const { addNotification, markJudgmentAsRead } = useNotifications();
  // 처리 중인 항목 id set — 중복 클릭 방지
  const [processingIds, setProcessingIds] = useState(new Set());

  // ESC로 닫기
  useEffect(() => {
    const onKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const setProcessing = (id, on) => {
    setProcessingIds((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const handleApprove = async (decision) => {
    const id = decision.id;
    if (processingIds.has(id)) return;
    setProcessing(id, true);
    try {
      const result = await approveDecision(id);
      toast.success(`승인 완료: ${decision.stockCode}`, {
        description: `주문번호: ${result?.orderId ?? '-'}`,
      });
      addNotification({
        type: 'APPROVED',
        message: `AI 판단 승인: ${decision.stockCode}`,
        description: `${sideToLabel(decision.decision)} · 주문번호 ${result?.orderId ?? '-'}`,
        judgmentId: id,
      });
      markJudgmentAsRead(id);
      refresh();
    } catch (error) {
      handleActionError(error, decision, '승인');
    } finally {
      setProcessing(id, false);
    }
  };

  const handleReject = async (decision) => {
    const id = decision.id;
    if (processingIds.has(id)) return;
    setProcessing(id, true);
    try {
      await rejectDecision(id);
      toast.success(`거부 완료: ${decision.stockCode}`);
      addNotification({
        type: 'REJECTED',
        message: `AI 판단 거부: ${decision.stockCode}`,
        description: `${sideToLabel(decision.decision)} · 사용자가 거부함`,
        judgmentId: id,
      });
      markJudgmentAsRead(id);
      refresh();
    } catch (error) {
      handleActionError(error, decision, '거부');
    } finally {
      setProcessing(id, false);
    }
  };

  const handleActionError = (error, decision, actionLabel) => {
    const code = error?.status;
    if (code === 410) {
      toast.warning(`${decision.stockCode} 판단이 이미 만료되었습니다`);
    } else if (code === 409) {
      toast.warning(`${decision.stockCode} 판단이 이미 처리되었습니다`);
    } else if (code === 403) {
      toast.error('권한이 없습니다');
    } else {
      toast.error(`${actionLabel} 실패: ${error?.message ?? '알 수 없는 오류'}`);
    }
    refresh();
  };

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) onClose();
  };

  return (
    <div className="pending-modal-backdrop" onClick={handleBackdropClick} role="dialog" aria-modal="true">
      <div className="pending-modal" role="document">
        <header className="pending-modal-header">
          <div className="pending-modal-title-group">
            <h2 className="pending-modal-title">AI 판단 승인 대기</h2>
            <span className="pending-modal-count">{pending.length}건</span>
          </div>
          <button type="button" className="pending-modal-close" onClick={onClose} aria-label="닫기">
            <X size={20} />
          </button>
        </header>

        <div className="pending-modal-body">
          {pending.length === 0 ? (
            <div className="pending-modal-empty">
              승인 대기 중인 AI 판단이 없습니다.
            </div>
          ) : (
            pending.map((d) => {
              const remainingMs = d.approvalExpiresAt
                ? new Date(d.approvalExpiresAt).getTime() - now
                : 0;
              const remaining = formatRemaining(remainingMs);
              const isExpired = remainingMs <= 0;
              const isProcessing = processingIds.has(d.id);
              const sideLabel = sideToLabel(d.decision);
              const riskClass = riskClassName(d.riskLevel);

              return (
                <div key={d.id} className={`pending-card ${isExpired ? 'is-expired' : ''}`}>
                  <div className="pending-card-row pending-card-top">
                    <div className="pending-card-stock">
                      <span className="pending-card-stock-code">{d.stockCode ?? '-'}</span>
                      <span className={`pending-card-side side-${(d.decision ?? '').toLowerCase()}`}>
                        {sideLabel}
                      </span>
                      {d.riskLevel && (
                        <span className={`pending-card-risk ${riskClass}`}>
                          위험도 {d.riskLevel}
                        </span>
                      )}
                    </div>
                    <div className={`pending-card-countdown ${remainingMs < 60 * 1000 ? 'is-urgent' : ''}`}>
                      {isExpired ? '만료됨' : remaining}
                    </div>
                  </div>

                  <p className="pending-card-reason">{d.reasonSummary ?? '판단 사유 없음'}</p>

                  <div className="pending-card-meta">
                    {d.orderAmount != null && (
                      <span><em>주문 금액</em>{Number(d.orderAmount).toLocaleString()}원</span>
                    )}
                    {d.targetPrice != null && (
                      <span><em>목표가</em>{Number(d.targetPrice).toLocaleString()}원</span>
                    )}
                    {d.stopLossPrice != null && (
                      <span><em>손절가</em>{Number(d.stopLossPrice).toLocaleString()}원</span>
                    )}
                    {d.confidenceScore != null && (
                      <span><em>신뢰도</em>{d.confidenceScore}%</span>
                    )}
                  </div>

                  <div className="pending-card-actions">
                    <button
                      type="button"
                      className="pending-card-btn pending-card-btn-reject"
                      onClick={() => handleReject(d)}
                      disabled={isProcessing || isExpired}
                    >
                      거부
                    </button>
                    <button
                      type="button"
                      className="pending-card-btn pending-card-btn-approve"
                      onClick={() => handleApprove(d)}
                      disabled={isProcessing || isExpired}
                    >
                      {isProcessing ? '처리 중…' : '승인'}
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}

function sideToLabel(decision) {
  if (!decision) return '판단';
  const upper = String(decision).toUpperCase();
  if (upper === 'BUY') return '매수';
  if (upper === 'SELL') return '매도';
  if (upper === 'HOLD') return '관망';
  return decision;
}

function riskClassName(riskLevel) {
  const lower = String(riskLevel ?? '').toLowerCase();
  if (lower.includes('high')) return 'risk-high';
  if (lower.includes('low')) return 'risk-low';
  return 'risk-medium';
}

function formatRemaining(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '0:00';
  const totalSec = Math.floor(ms / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}
