import { useState, useEffect } from 'react';
import { getStockNews } from '../api/market';
import './TradingNews.css';



function toRelativeTime(isoString) {
  const diff = Math.floor((Date.now() - new Date(isoString).getTime()) / 1000);
  if (diff < 60) return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86400)}일 전`;
}

export default function TradingNews({ stockCode }) {
  const [newsList, setNewsList] = useState([]);

  useEffect(() => {
    if (!stockCode) return;
    let cancelled = false;
    async function fetchNews() {
      try {
        const news = await getStockNews(stockCode);
        if (!cancelled) setNewsList(news ?? []);
      } catch (error) {
        if (!cancelled) console.error('뉴스 데이터 로드 실패:', error);
      }
    }
    fetchNews();
    return () => { cancelled = true; };
  }, [stockCode]);

  const handleNewsClick = (url) => {
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
  };

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
          newsList.map((news, idx) => (
            <div key={idx} className="news-item" onClick={() => handleNewsClick(news.url)}>
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
    </div>
  );
}
