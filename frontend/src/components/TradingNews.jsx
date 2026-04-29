import { useState } from 'react';
import './TradingNews.css';

const TABS = ['뉴스'];

const MOCK_NEWS = [
  { title: "삼성전자, 6세대 HBM 양산 본격화… 주가 상승 반전", source: '한국경제', time: '40분 전' },
  { title: '[단독] SK하이닉스, 엔비디아 향 HBM3E 물량 추가 확보 성공', source: '매일경제', time: '2시간 전', trending: true },
  { title: "외국인 투자자들, 반도체 대형주 중심으로 강한 순매수세", source: '연합인포맥스', time: '2시간 전' },
  { title: '금투세 폐지 기대감 속, 코스피 2800선 재돌파 시도', source: '블룸버그', time: '3시간 전' },
];

export default function TradingNews() {
  const [activeTab, setActiveTab] = useState('뉴스');

  return (
    <div className="news-wrapper">
      <div className="news-tabs">
        {TABS.map(tab => (
          <button 
            key={tab} 
            className={`tab-btn ${activeTab === tab ? 'active' : ''}`}
            onClick={() => setActiveTab(tab)}
          >
            {tab}
          </button>
        ))}
      </div>
      <div className="news-list">
        {MOCK_NEWS.map((news, idx) => (
          <div key={idx} className="news-item">
            <div className="news-content">
              {news.trending && <span className="tag-trending">Trending</span>}
              <span className="news-title">{news.title}</span>
            </div>
            <div className="news-meta">
              <span className="news-source">{news.source}</span>
              <span className="news-dot">·</span>
              <span className="news-time">{news.time}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
