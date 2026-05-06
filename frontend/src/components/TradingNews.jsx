import { useState } from 'react';
// import { useEffect } from 'react';
// import { getStockNews } from '../api/market';
import './TradingNews.css';

// ── MOCK 뉴스 데이터 (백엔드 연동 후 삭제 예정) ───────────────────────────────
const MOCK_NEWS = [
  {
    title: '삼성전자, 3나노 파운드리 수율 개선…하반기 실적 기대감 ↑',
    source: '한국경제',
    publishedAt: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
    url: 'https://example.com/news/1',
  },
  {
    title: 'SK하이닉스, HBM4 공급 계약 체결…엔비디아와 협력 강화',
    source: '매일경제',
    publishedAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    url: 'https://example.com/news/2',
  },
  {
    title: '미 연준 금리 동결 시사…국내 증시 외국인 순매수 전환',
    source: '블룸버그',
    publishedAt: new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString(),
    url: 'https://example.com/news/3',
  },
  {
    title: '코스피 2,600선 회복…반도체·2차전지 동반 강세',
    source: '연합뉴스',
    publishedAt: new Date(Date.now() - 5 * 60 * 60 * 1000).toISOString(),
    url: 'https://example.com/news/4',
  },
];
// ─────────────────────────────────────────────────────────────────────────────

function toRelativeTime(isoString) {
  const diff = Math.floor((Date.now() - new Date(isoString).getTime()) / 1000);
  if (diff < 60)  return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86400)}일 전`;
}

export default function TradingNews({ stockCode }) {
  const [newsList] = useState(MOCK_NEWS);

  // ── 연동 시 위 useState(MOCK_NEWS) → useState([]) 로 교체하고 아래 블록 해제 ─
  // const [newsList, setNewsList] = useState([]);
  //
  // useEffect(() => {
  //   if (!stockCode) return;
  //   async function fetchNews() {
  //     try {
  //       const news = await getStockNews(stockCode);
  //       // news: [{ title, source, publishedAt, url }, ...]
  //       setNewsList(news);
  //     } catch (error) {
  //       console.error('뉴스 데이터 로드 실패:', error);
  //     }
  //   }
  //   fetchNews();
  // }, [stockCode]);
  // ─────────────────────────────────────────────────────────────────────────

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
