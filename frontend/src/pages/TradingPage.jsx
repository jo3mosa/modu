import { useState, useEffect, useRef, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search, ArrowLeft, TrendingUp, BarChart2 } from 'lucide-react';
import TradingChart from '../components/TradingChart';
import TradingNews from '../components/TradingNews';
import OrderBook from '../components/OrderBook';
import { getStocks, getStockDetail } from '../api/market';
import { buildStockWsUrl } from '../api/wsUrl';
import './TradingPage.css';

/**
 * 백엔드 StockDetailResponse에는 `compareRate`(전일 대비율 %)는 있지만
 * 절대 변화량(`changeAmount`)은 없다. currentPrice와 compareRate로 역산한다.
 */
function computeChangeAmount(currentPrice, compareRate) {
  if (currentPrice == null || compareRate == null) return 0;
  const denom = 100 + compareRate;
  if (denom === 0) return 0;
  return Math.round((currentPrice * compareRate) / denom);
}

// 한글 초성 추출 유틸리티
function getChoseong(str) {
  const cho = ["ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ","ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"];
  let result = "";
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i) - 44032;
    if (code > -1 && code < 11172) {
      result += cho[Math.floor(code / 588)];
    } else {
      result += str.charAt(i);
    }
  }
  return result;
}
// 전역 모듈 수준의 종목 리스트 캐시 (나갔다 들어올 때 빈번한 갱신 방지 및 시총순 정렬 고정)
let cachedAllStocksList = null;

// KIS API 초당 호출제한(Rate Limit)으로 인한 세부정보 실패 시에도 완벽한 시총순 정렬을 보장하기 위한 정적 시총 매핑 사전
const MAJOR_STOCKS_CAP_MAP = {
  '005930': 489000000000000, // 삼성전자
  '000660': 136000000000000, // SK하이닉스
  '373220': 90000000000000,  // LG에너지솔루션
  '207940': 60000000000000,  // 삼성바이오로직스
  '005380': 50000000000000,  // 현대차
  '000270': 45000000000000,  // 기아
  '068270': 40000000000000,  // 셀트리온
  '105560': 35000000000000,  // KB금융
  '055550': 32000000000000,  // 신한지주
  '005490': 30000000000000,  // POSCO홀딩스 (포스코홀딩스)
  '005935': 29000000000000,  // 삼성전자우
  '035420': 28000000000000,  // NAVER (네이버)
  '028260': 25000000000000,  // 삼성물산
  '006400': 24000000000000,  // 삼성SDI
  '051910': 23000000000000,  // LG화학
  '035720': 22000000000000,  // 카카오
  '032830': 20000000000000,  // 삼성생명
  '012330': 19000000000000,  // 현대모비스
  '138040': 18000000000000,  // 메리츠금융지주
  '086790': 17000000000000,  // 하나금융지주
  '066570': 16000000000000,  // LG전자
  '259960': 15000000000000,  // 크래프톤
  '003550': 14500000000000,  // LG
  '015760': 14000000000000,  // 한국전력
  '000810': 13000000000000,  // 삼성화재
  '017670': 12000000000000,  // SK텔레콤
  '033780': 11500000000000,  // KT&G
  '011200': 11000000000000,  // HMM
  '316140': 10000000000000,  // 우리금융지주
  '009540': 9500000000000,   // HD현대중공업
  '034020': 9000000000000,   // 두산에너빌리티
  '323410': 8500000000000,   // 카카오뱅크
  '030200': 8200000000000,   // KT
  '009150': 8000000000000,   // 삼성전기
  '010950': 7500000000000,   // S-Oil
  '032640': 7000000000000,   // LG유플러스
  '377300': 6500000000000,   // 카카오페이
  '036570': 6000000000000,   // 엔씨소프트
  '090430': 5500000000000,   // 아모레퍼시픽
  '097950': 5000000000000,   // CJ제일제당
  '000100': 4800000000000,   // 유한양행
  '000720': 4500000000000,   // 현대건설
  '010140': 4200000000000,   // 삼성중공업
  '042700': 4000000000000,   // 한미반도체
  '192820': 3800000000000,   // 코스맥스
  '247540': 3500000000000,   // 에코프로비엠
  '086520': 3200000000000,   // 에코프로
  '397600': 3000000000000,   // L&F / 엘앤에프
  '028300': 2800000000000,   // HLB
  '214150': 2500000000000,   // 클래시스
  '036830': 2200000000000,   // 솔브레인
  '035900': 2000000000000,   // JYP Ent.
  '253450': 1800000000000,   // 스튜디오드래곤
  '195870': 1600000000000,   // 해성디에스
  '058470': 1500000000000,   // 리노공업
  '293490': 1400000000000,   // 카카오게임즈
  '112040': 1200000000000,   // 위메이드
  '263750': 1000000000000,   // 펄어비스
  '034730': 800000000000,    // SK
  '096770': 750000000000,    // SK이노베이션
  '326030': 700000000000,    // SK바이오팜
  '302440': 650000000000,    // SK바이오사이언스
  '011790': 600000000000,    // SKC
  '011170': 550000000000,    // 롯데케미칼
  '004990': 500000000000,    // 롯데지주
  '251270': 450000000000,    // 넷마블
  '267250': 400000000000,    // HD현대
  '071050': 350000000000,    // 한국금융지주
};

// 주요 60대 대형주 정적 폴백 사전 (초성 즉각 검색 및 API timing 이슈 방어)
const MAJOR_STOCKS = [
  { stockCode: '005930', stockName: '삼성전자', marketType: 'KOSPI' },
  { stockCode: '000660', stockName: 'SK하이닉스', marketType: 'KOSPI' },
  { stockCode: '035720', stockName: '카카오', marketType: 'KOSPI' },
  { stockCode: '035420', stockName: 'NAVER', marketType: 'KOSPI' },
  { stockCode: '035420', stockName: '네이버', marketType: 'KOSPI' },
  { stockCode: '005380', stockName: '현대차', marketType: 'KOSPI' },
  { stockCode: '000270', stockName: '기아', marketType: 'KOSPI' },
  { stockCode: '373220', stockName: 'LG에너지솔루션', marketType: 'KOSPI' },
  { stockCode: '207940', stockName: '삼성바이오로직스', marketType: 'KOSPI' },
  { stockCode: '006400', stockName: '삼성SDI', marketType: 'KOSPI' },
  { stockCode: '051910', stockName: 'LG화학', marketType: 'KOSPI' },
  { stockCode: '005490', stockName: 'POSCO홀딩스', marketType: 'KOSPI' },
  { stockCode: '005490', stockName: '포스코홀딩스', marketType: 'KOSPI' },
  { stockCode: '068270', stockName: '셀트리온', marketType: 'KOSPI' },
  { stockCode: '005935', stockName: '삼성전자우', marketType: 'KOSPI' },
  { stockCode: '003550', stockName: 'LG', marketType: 'KOSPI' },
  { stockCode: '032830', stockName: '삼성생명', marketType: 'KOSPI' },
  { stockCode: '015760', stockName: '한국전력', marketType: 'KOSPI' },
  { stockCode: '000810', stockName: '삼성화재', marketType: 'KOSPI' },
  { stockCode: '033780', stockName: 'KT&G', marketType: 'KOSPI' },
  { stockCode: '009150', stockName: '삼성전기', marketType: 'KOSPI' },
  { stockCode: '010950', stockName: 'S-Oil', marketType: 'KOSPI' },
  { stockCode: '086790', stockName: '하나금융지주', marketType: 'KOSPI' },
  { stockCode: '055550', stockName: '신한지주', marketType: 'KOSPI' },
  { stockCode: '105560', stockName: 'KB금융', marketType: 'KOSPI' },
  { stockCode: '030200', stockName: 'KT', marketType: 'KOSPI' },
  { stockCode: '017670', stockName: 'SK텔레콤', marketType: 'KOSPI' },
  { stockCode: '032640', stockName: 'LG유플러스', marketType: 'KOSPI' },
  { stockCode: '011200', stockName: 'HMM', marketType: 'KOSPI' },
  { stockCode: '323410', stockName: '카카오뱅크', marketType: 'KOSPI' },
  { stockCode: '377300', stockName: '카카오페이', marketType: 'KOSPI' },
  { stockCode: '259960', stockName: '크래프톤', marketType: 'KOSPI' },
  { stockCode: '036570', stockName: '엔씨소프트', marketType: 'KOSPI' },
  { stockCode: '090430', stockName: '아모레퍼시픽', marketType: 'KOSPI' },
  { stockCode: '097950', stockName: 'CJ제일제당', marketType: 'KOSPI' },
  { stockCode: '000100', stockName: '유한양행', marketType: 'KOSPI' },
  { stockCode: '000720', stockName: '현대건설', marketType: 'KOSPI' },
  { stockCode: '010140', stockName: '삼성중공업', marketType: 'KOSPI' },
  { stockCode: '009540', stockName: 'HD현대중공업', marketType: 'KOSPI' },
  { stockCode: '042700', stockName: '한미반도체', marketType: 'KOSPI' },
  { stockCode: '138040', stockName: '메리츠금융지주', marketType: 'KOSPI' },
  { stockCode: '192820', stockName: '코스맥스', marketType: 'KOSPI' },
  { stockCode: '247540', stockName: '에코프로비엠', marketType: 'KOSDAQ' },
  { stockCode: '086520', stockName: '에코프로', marketType: 'KOSDAQ' },
  { stockCode: '397600', stockName: 'L&F', marketType: 'KOSDAQ' },
  { stockCode: '028300', stockName: 'HLB', marketType: 'KOSDAQ' },
  { stockCode: '214150', stockName: '클래시스', marketType: 'KOSDAQ' },
  { stockCode: '036830', stockName: '솔브레인', marketType: 'KOSDAQ' },
  { stockCode: '035900', stockName: 'JYP Ent.', marketType: 'KOSDAQ' },
  { stockCode: '253450', stockName: '스튜디오드래곤', marketType: 'KOSDAQ' },
  { stockCode: '195870', stockName: '해성디에스', marketType: 'KOSDAQ' },
  { stockCode: '058470', stockName: '리노공업', marketType: 'KOSDAQ' },
  { stockCode: '293490', stockName: '카카오게임즈', marketType: 'KOSDAQ' },
  { stockCode: '112040', stockName: '위메이드', marketType: 'KOSDAQ' },
  { stockCode: '263750', stockName: '펄어비스', marketType: 'KOSDAQ' },
];

export default function TradingPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const stockCode = searchParams.get('stock'); // null 이면 목록 홈 노출

  // 1. 종목 목록 홈용 상태
  const [allStocksList, setAllStocksList] = useState(cachedAllStocksList ?? []);
  const [stocks, setStocks] = useState([]);
  const [currentPage, setCurrentPage] = useState(1);
  const [isLoadingStocks, setIsLoadingStocks] = useState(false);
  const pageSize = 20;

  // 2. 단일 종목 상세용 상태
  const [stockDetail, setStockDetail] = useState(null);
  const [wsStatus, setWsStatus] = useState('connecting'); // 'connecting' | 'live' | 'disconnected'

  // ── [FLOW A-1] 전체 종목 메타 목록 조회 (시세 미포함 - 로컬 DB 100% 안전 쿼리) ──
  useEffect(() => {
    if (stockCode) return; // 상세 뷰일 때는 미작동
    if (allStocksList.length > 0) return; // 이미 로드된 경우 패스

    let cancelled = false;
    async function loadAllStocksMeta() {
      setIsLoadingStocks(true);
      try {
        const data = await getStocks({ page: 1, size: 100 }); // 100개 메타데이터 초고속 쿼리
        if (cancelled) return;
        const apiList = data?.stocks ?? [];

        // 대형주 사전과 API 응답 종목 리스트를 병합하여 중복 제거
        const combinedMap = new Map();
        MAJOR_STOCKS.forEach((item) => {
          combinedMap.set(item.stockCode, {
            stockCode: item.stockCode,
            stockName: item.stockName,
            marketType: item.marketType,
          });
        });
        apiList.forEach((item) => {
          if (!combinedMap.has(item.stockCode)) {
            combinedMap.set(item.stockCode, item);
          }
        });

        const list = Array.from(combinedMap.values());

        // 시가총액 기준으로 정렬하여 전역 모듈 캐싱 및 상태 저장
        list.sort((a, b) => {
          const capA = MAJOR_STOCKS_CAP_MAP[a.stockCode] || 0;
          const capB = MAJOR_STOCKS_CAP_MAP[b.stockCode] || 0;
          if (capA === 0 && capB === 0) {
            return a.stockCode.localeCompare(b.stockCode);
          }
          return Number(capB - capA);
        });

        if (!cancelled) {
          cachedAllStocksList = list;
          setAllStocksList(list);
        }
      } catch (err) {
        console.error('전체 종목 목록 메타 로드 실패:', err);
      } finally {
        if (!cancelled) setIsLoadingStocks(false);
      }
    }

    loadAllStocksMeta();
    return () => {
      cancelled = true;
    };
  }, [stockCode, allStocksList.length]);

  // ── [FLOW A-2] 현재 페이지의 20개 종목에 대한 실시간 KIS API 시세 병렬 병합 (5초 Polling으로 웹소켓 제거 & 안전 갱신) ──
  useEffect(() => {
    if (stockCode || allStocksList.length === 0) return;

    let cancelled = false;
    let isFirstLoad = true;

    async function loadPageDetails() {
      if (isFirstLoad) {
        setIsLoadingStocks(true);
      }
      
      const pageItems = allStocksList.slice((currentPage - 1) * pageSize, currentPage * pageSize);

      try {
        const details = await Promise.all(
          pageItems.map(async (s) => {
            try {
              const detail = await getStockDetail(s.stockCode);
              return {
                ...s,
                ...detail,
                flashDirection: null,
                flashTime: 0
              };
            } catch {
              return {
                ...s,
                currentPrice: 0,
                compareRate: 0,
                accumulatedVolume: 0,
                flashDirection: null,
                flashTime: 0
              };
            }
          })
        );

        if (!cancelled) {
          setStocks((prevStocks) => {
            // 기존 데이터와 비교하여 가격 등락에 따른 플래시 방향 계산
            return details.map((newStock) => {
              const prevStock = prevStocks.find((ps) => ps.stockCode === newStock.stockCode);
              if (prevStock && prevStock.currentPrice > 0 && newStock.currentPrice > 0) {
                let flash = null;
                if (newStock.currentPrice > prevStock.currentPrice) flash = 'up';
                else if (newStock.currentPrice < prevStock.currentPrice) flash = 'down';

                return {
                  ...newStock,
                  flashDirection: flash,
                  flashTime: flash ? Date.now() : prevStock.flashTime
                };
              }
              return newStock;
            });
          });
          isFirstLoad = false;
        }
      } catch (err) {
        console.error('페이지 상세 시세 로드 실패:', err);
      } finally {
        if (!cancelled && isFirstLoad) setIsLoadingStocks(false);
      }
    }

    loadPageDetails();

    const intervalId = setInterval(() => {
      loadPageDetails();
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, [stockCode, allStocksList, currentPage]);

  // ── [FLOW B] 단일 종목 상세 REST 데이터 조회 ──
  useEffect(() => {
    if (!stockCode) return;
    let cancelled = false;
    async function fetchDetail() {
      try {
        const data = await getStockDetail(stockCode);
        if (!cancelled) setStockDetail(data);
      } catch (error) {
        if (cancelled) return;
        console.error('종목 상세 로드 실패:', error);
        setStockDetail(null);
      }
    }
    fetchDetail();
    return () => {
      cancelled = true;
    };
  }, [stockCode]);

  // ── [FLOW B] 단일 종목 상세 실시간 체결가 WebSocket 연동 ──
  useEffect(() => {
    if (!stockCode) return;

    setWsStatus('connecting');
    const ws = new WebSocket(buildStockWsUrl(stockCode, 'price'));
    let intentionalClose = false;

    ws.onopen = () => {
      setWsStatus('live');
    };

    ws.onmessage = (event) => {
      try {
        const tick = JSON.parse(event.data);
        setStockDetail((prev) =>
          prev
            ? {
                ...prev,
                currentPrice: tick.currentPrice ?? prev.currentPrice,
                compareRate: tick.priceChangeRate ?? prev.compareRate,
                highPrice: tick.highPrice ?? prev.highPrice,
                lowPrice: tick.lowPrice ?? prev.lowPrice,
                accumulatedVolume: tick.accumulatedVolume ?? prev.accumulatedVolume,
              }
            : prev
        );
      } catch (error) {
        console.error('실시간 체결가 메시지 파싱 실패:', error);
      }
    };

    ws.onerror = () => {
      if (!intentionalClose) setWsStatus('disconnected');
    };

    ws.onclose = () => {
      if (!intentionalClose) setWsStatus('disconnected');
    };

    return () => {
      intentionalClose = true;
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    };
  }, [stockCode]);

  // ============================================
  // 1. [RENDER A] 종목 목록 그리드 홈 화면
  // ============================================
  if (!stockCode) {
    return (
      <div className="trading-container">
        <div className="page-header-container">
          <div className="page-title-group">
            <h1>트레이딩 룸</h1>
            <p>실시간 시세를 보며 거래를 시작할 종목을 선택하세요.</p>
          </div>
        </div>

        {/* 로딩 뷰 */}
        {isLoadingStocks && stocks.length === 0 ? (
          <div className="selector-loading-container">
            <div className="selector-spinner" />
            <p>실시간 시세 피드를 준비하고 있습니다…</p>
          </div>
        ) : (
          <>
            {stocks.length === 0 ? (
              <div className="selector-empty-container">
                <p>일치하는 종목이 존재하지 않습니다.</p>
              </div>
            ) : (
              <div className="stock-selector-grid">
                {stocks.map((s) => {
                  const isUp = s.compareRate >= 0;
                  const changeColor = isUp ? 'up' : 'down';
                  const changeAmount = computeChangeAmount(s.currentPrice, s.compareRate);

                  // 실시간 플래시 효과 지속 여부 점검 (1초 이내)
                  const hasFlash = s.flashDirection && (Date.now() - s.flashTime < 1000);
                  const flashClass = hasFlash ? `flash-${s.flashDirection}` : '';

                  return (
                    <article
                      key={s.stockCode}
                      className="stock-summary-card"
                      onClick={() => setSearchParams({ stock: s.stockCode })}
                    >
                      <header className="card-header">
                        <div className="card-identity">
                          <h3 className="card-name">{s.stockName}</h3>
                          <span className="card-code">{s.stockCode}</span>
                        </div>
                        <span className={`card-market-badge ${s.marketType?.toLowerCase()}`}>
                          {s.marketType ?? 'KOSPI'}
                        </span>
                      </header>

                      <div className="card-body">
                        <div className={`card-price ${flashClass}`}>
                          {s.currentPrice > 0 ? `${s.currentPrice.toLocaleString()}원` : '-'}
                        </div>
                        <div className={`card-change ${changeColor}`}>
                          {isUp ? '+' : ''}{changeAmount.toLocaleString()}원
                          &nbsp;({isUp ? '+' : ''}{s.compareRate.toFixed(2)}%)
                        </div>
                      </div>

                      <footer className="card-footer">
                        <div className="card-volume">
                          <span>거래량</span>
                          <strong>{s.accumulatedVolume?.toLocaleString() ?? '0'} 주</strong>
                        </div>
                        <div className="card-action-btn">
                          <TrendingUp size={16} /> 거래하기
                        </div>
                      </footer>
                    </article>
                  );
                })}
              </div>
            )}

            {/* Premium Pagination Control Bar */}
            {allStocksList.length > 0 && (
              <div className="pagination-bar">
                <button
                  type="button"
                  className="pagination-btn arrow"
                  disabled={currentPage === 1 || isLoadingStocks}
                  onClick={() => {
                    setCurrentPage((prev) => Math.max(prev - 1, 1));
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                  }}
                >
                  &lt; 이전
                </button>
                
                {Array.from({ length: Math.ceil(allStocksList.length / pageSize) }).map((_, idx) => {
                  const pageNum = idx + 1;
                  const isActive = currentPage === pageNum;
                  return (
                    <button
                      key={pageNum}
                      type="button"
                      className={`pagination-btn num ${isActive ? 'active' : ''}`}
                      disabled={isLoadingStocks}
                      onClick={() => {
                        setCurrentPage(pageNum);
                        window.scrollTo({ top: 0, behavior: 'smooth' });
                      }}
                    >
                      {pageNum}
                    </button>
                  );
                })}

                <button
                  type="button"
                  className="pagination-btn arrow"
                  disabled={currentPage === Math.ceil(allStocksList.length / pageSize) || isLoadingStocks}
                  onClick={() => {
                    setCurrentPage((prev) => Math.min(prev + 1, Math.ceil(allStocksList.length / pageSize)));
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                  }}
                >
                  다음 &gt;
                </button>
              </div>
            )}
          </>
        )}
      </div>
    );
  }

  // ============================================
  // 2. [RENDER B] 단일 종목 상세 거래 화면
  // ============================================
  if (!stockDetail) {
    return (
      <div className="trading-container">
        <div className="selector-loading-container">
          <div className="selector-spinner" />
          <p>종목 상세 데이터를 불러오고 있습니다…</p>
        </div>
      </div>
    );
  }

  const compareRate = stockDetail.compareRate ?? 0;
  const isUp = compareRate >= 0;
  const changeColor = isUp ? 'up' : 'down';
  const changeAmount = computeChangeAmount(stockDetail.currentPrice, compareRate);

  return (
    <div className="trading-container">
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>트레이딩 룸</h1>
          <p>실시간 호가와 차트를 보며 매매를 진행하세요.</p>
        </div>
      </div>

      {/* 선택 종목 정보 스트립 */}
      <div className="stock-info-strip">
        <div className="stock-identity">
          <button
            type="button"
            className="back-to-list-btn"
            onClick={() => setSearchParams({})}
            title="종목 목록 홈으로 가기"
          >
            <ArrowLeft size={16} />
            목록으로
          </button>
          <span className="stock-strip-name">{stockDetail.stockName}</span>
          <span className="stock-strip-code">{stockCode}</span>
        </div>
        <div className="stock-price-group">
          <span className="stock-strip-price">{stockDetail.currentPrice.toLocaleString()}원</span>
          <span className={`stock-strip-change ${changeColor}`}>
            {isUp ? '+' : ''}{changeAmount.toLocaleString()}원
            &nbsp;({isUp ? '+' : ''}{compareRate.toFixed(2)}%)
          </span>
        </div>
        <div className="stock-meta-group">
          <span className="meta-item"><em>거래량</em>{stockDetail.accumulatedVolume?.toLocaleString() ?? '-'}</span>
          <span className="meta-item"><em>고</em><span className="up">{stockDetail.highPrice?.toLocaleString() ?? '-'}</span></span>
          <span className="meta-item"><em>저</em><span className="down">{stockDetail.lowPrice?.toLocaleString() ?? '-'}</span></span>
        </div>
      </div>

      <div className="trading-top">
        <div className="chart-section">
          <TradingChart stockCode={stockCode} />
        </div>
        <div className="order-section">
          <OrderBook stockCode={stockCode} />
        </div>
      </div>

      <div className="news-section">
        <TradingNews stockCode={stockCode} />
      </div>
    </div>
  );
}
