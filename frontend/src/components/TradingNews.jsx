import { useState } from 'react';
import './TradingNews.css';

const TABS = ['뉴스'];

const MOCK_NEWS = [
  { title: "삼성전자, 어디까지 가나요!", source: '한국경제', time: '40분 전', trending: true },
  { title: 'SK하이닉스, 어디까지 가나요!', source: '매일경제', time: '2시간 전', trending: true },
  { title: '전쟁은 언제 끝날까요?', source: '블룸버그', time: '3시간 전' },
];

export default function TradingNews() {
  const [activeTab, setActiveTab] = useState('뉴스');

  // 연동 시 주석 해제 필요 !!
  /*
  const [newsList, setNewsList] = useState(MOCK_NEWS);

  useEffect(() => {
    const fetchNews = async () => {
      try {
        const response = await fetch('/api/v1/markets/stocks/005930/news');
        const data = await response.json();
        // data: [{ title: '...', source: '...', time: '...' }, ...]
        setNewsList(data);
      } catch (error) {
        console.error("뉴스 데이터 로드 실패:", error);
      }
    };
    // fetchNews();
  }, [activeTab]); // 탭이 바뀔 때마다 다시 fetch 할 수도 있음
  */

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
