import { useState, useEffect, useMemo } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { getStockNews } from '../api/market';
import './TradingNews.css';

const NEWS_PER_PAGE = 5;

function toRelativeTime(isoString) {
  const diff = Math.floor((Date.now() - new Date(isoString).getTime()) / 1000);
  if (diff < 60) return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86400)}일 전`;
}

export default function TradingNews({ stockCode }) {
  const [newsList, setNewsList] = useState([]);
  const [page, setPage] = useState(1);

  useEffect(() => {
    if (!stockCode) return;
    let cancelled = false;
    async function fetchNews() {
      try {
        const news = await getStockNews(stockCode);
        if (!cancelled) {
          setNewsList(news ?? []);
          setPage(1); // 종목 변경 시 첫 페이지로 리셋
        }
      } catch (error) {
        if (!cancelled) console.error('뉴스 데이터 로드 실패:', error);
      }
    }
    fetchNews();
    return () => { cancelled = true; };
  }, [stockCode]);

  const totalPages = Math.max(1, Math.ceil(newsList.length / NEWS_PER_PAGE));

  // 데이터가 줄어 현재 페이지가 범위를 벗어나면 마지막 페이지로 보정
  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);

  const visibleNews = useMemo(() => {
    const start = (page - 1) * NEWS_PER_PAGE;
    return newsList.slice(start, start + NEWS_PER_PAGE);
  }, [newsList, page]);

  const handleNewsClick = (url) => {
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
  };

  const goPrev = () => setPage((p) => Math.max(1, p - 1));
  const goNext = () => setPage((p) => Math.min(totalPages, p + 1));

  return (
    <div className="news-wrapper">
      <div className="news-header">
        <span className="news-header-title">관련 뉴스</span>
        <span className="news-count">{newsList.length}건</span>
      </div>
      <div className="news-list">
        {newsList.length === 0 ? (
          <div className="news-empty">관련 뉴스가 없습니다.</div>
        ) : (
          visibleNews.map((news, idx) => (
            <div key={`${page}-${idx}`} className="news-item" onClick={() => handleNewsClick(news.url)}>
              <div className="news-content">
                <span className="news-title">{news.title}</span>
              </div>
              <div className="news-meta">
                <span className="news-source">{news.source}</span>
                <span className="news-dot">·</span>
                <span className="news-time">{toRelativeTime(news.publishedAt)}</span>
                <span className="news-link-icon">↗</span>
              </div>
            </div>
          ))
        )}
      </div>

      {/* 페이지네이션 — 2페이지 이상일 때만 표시 */}
      {totalPages > 1 && (
        <div className="news-pagination">
          <button
            type="button"
            className="news-page-btn"
            onClick={goPrev}
            disabled={page === 1}
            aria-label="이전 페이지"
          >
            <ChevronLeft size={16} />
          </button>
          <span className="news-page-indicator">
            {page} / {totalPages}
          </span>
          <button
            type="button"
            className="news-page-btn"
            onClick={goNext}
            disabled={page === totalPages}
            aria-label="다음 페이지"
          >
            <ChevronRight size={16} />
          </button>
        </div>
      )}
    </div>
  );
}
