import { useMemo, useState } from 'react';
import { discoveryMockData, DISCOVERY_FILTERS } from '../mocks/discoveryMock';
import './DiscoveryPage.css';

const TIER_ORDER = ['T1', 'T2', 'T3', 'T4', 'T5'];

function formatPrice(price) {
  return price.toLocaleString('ko-KR');
}

function formatChangePct(pct) {
  const sign = pct > 0 ? '+' : '';
  return `${sign}${pct.toFixed(2)}%`;
}

function ProfileHero({ user, tierCounts }) {
  return (
    <section className="discovery-hero">
      <div className="discovery-hero-left">
        <div className="discovery-hero-eyebrow">
        </div>
        <div className="discovery-hero-grade">
          <span className="discovery-hero-grade-tier">{user.riskGrade}</span>
          <span className="discovery-hero-grade-label">{user.riskLabel}</span>
        </div>
        <p className="discovery-hero-note">
          현재 등급으로 <strong>{TIER_ORDER.slice(0, TIER_ORDER.indexOf(user.riskGrade) + 1).join('·')}</strong> 종목을 추천받습니다.{' '}
          <a className="discovery-hero-link" href="/risk-manage">등급 변경 →</a>
        </p>
      </div>

      <div className="discovery-hero-right">
        {TIER_ORDER.map((tier) => {
          const isMine = tier === user.riskGrade;
          const isLocked = TIER_ORDER.indexOf(tier) > TIER_ORDER.indexOf(user.riskGrade);
          return (
            <div
              key={tier}
              className={`tier-bar-col ${isMine ? 'is-mine' : ''} ${isLocked ? 'is-locked' : ''}`}
            >
              <div className="tier-bar-header">
                <span className="tier-bar-tier">{tier}</span>
                <span className="tier-bar-label">{discoveryMockData.tiers.find(t => t.tier === tier)?.label}</span>
              </div>
              {isMine && <span className="tier-bar-you">YOU</span>}
              <div className={`tier-bar-fill tier-bar-fill-${tier}`} />
              <div className="tier-bar-count">
                <strong>{tierCounts[tier] ?? 0}</strong>
                <span>종목</span>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function FilterChips({ active, onChange }) {
  return (
    <div className="discovery-filters">
      {DISCOVERY_FILTERS.map((f) => (
        <button
          key={f.key}
          type="button"
          className={`discovery-filter-chip ${active === f.key ? 'is-active' : ''}`}
          onClick={() => onChange(f.key)}
        >
          {f.label}
        </button>
      ))}
    </div>
  );
}

function StockCard({ stock, tier }) {
  const isUp = stock.changePct > 0;
  const isDown = stock.changePct < 0;

  return (
    <article className={`stock-card stock-card-${tier}`}>
      <header className="stock-card-header">
        <h3 className="stock-card-name">{stock.stockName}</h3>
        <div className="stock-card-meta">
          <span>{stock.stockCode}</span>
          <span className="stock-card-dot">·</span>
          <span>{stock.market}</span>
          <span className="stock-card-dot">·</span>
          <span>{stock.sector}</span>
        </div>
      </header>

      <div className="stock-card-price-row">
        <span className="stock-card-price">{formatPrice(stock.price)}</span>
        <span className={`stock-card-change ${isUp ? 'up' : ''} ${isDown ? 'down' : ''}`}>
          {formatChangePct(stock.changePct)}
        </span>
      </div>

      <p className="stock-card-reason">{stock.reason}</p>

      <div className="stock-card-tags">
        {stock.tags.map((tag) => (
          <span key={tag} className="stock-card-tag">{tag}</span>
        ))}
      </div>

      <div className="stock-card-divider" />

      <dl className="stock-card-metrics">
        <div>
          <dt>ATR</dt>
          <dd>{stock.metrics.atr.toFixed(1)}%</dd>
        </div>
        <div>
          <dt>ROE</dt>
          <dd>{stock.metrics.roe.toFixed(1)}%</dd>
        </div>
        <div>
          <dt>PER</dt>
          <dd>{stock.metrics.per.toFixed(1)}</dd>
        </div>
      </dl>

      <footer className="stock-card-footer">
        <span className="stock-card-pulse" />
        <span>{stock.updatedAt} 업데이트</span>
      </footer>
    </article>
  );
}

function TierSection({ tierData }) {
  const { tier, label, description, stocks } = tierData;
  return (
    <section className={`tier-section tier-section-${tier}`}>
      <header className="tier-section-header">
        <div className="tier-section-title-group">
          <span className={`tier-section-badge tier-section-badge-${tier}`}>{tier}</span>
          <h2 className="tier-section-title">{label}</h2>
        </div>
        <div className="tier-section-right">
          <span className="tier-section-count">{stocks.length}종목</span>
          <button type="button" className="tier-section-more">전체 보기 →</button>
        </div>
      </header>
      <p className="tier-section-description">{description}</p>

      <div className="tier-section-grid">
        {stocks.map((stock) => (
          <StockCard key={stock.stockCode} stock={stock} tier={tier} />
        ))}
      </div>
    </section>
  );
}

export default function DiscoveryPage() {
  const [activeFilter, setActiveFilter] = useState('ALL');
  const data = discoveryMockData;

  // 사용자 등급 이하 tier만 노출 + 필터 적용.
  // 실제로는 백엔드가 사용자 risk_grade 기준으로 이하만 응답하지만,
  // mock은 전체 tier를 갖고 있으므로 프론트에서 한 번 더 잘라낸다.
  const filteredTiers = useMemo(() => {
    const maxIdx = TIER_ORDER.indexOf(data.user.riskGrade);
    const visibleTiers = data.tiers.filter(
      (t) => TIER_ORDER.indexOf(t.tier) <= maxIdx
    );
    if (activeFilter === 'ALL') return visibleTiers;
    return visibleTiers
      .map((t) => ({
        ...t,
        stocks: t.stocks.filter((s) => s.filters?.includes(activeFilter)),
      }))
      .filter((t) => t.stocks.length > 0);
  }, [activeFilter, data.tiers, data.user.riskGrade]);

  return (
    <div className="discovery-container">
      {/* 다른 페이지와 동일한 글로벌 페이지 헤더 */}
      <div className="page-header-container">
        <div className="page-title-group">
          <h1>종목 추천</h1>
          <p>당신의 등급에 맞춰 추천합니다.</p>
        </div>
      </div>

      <ProfileHero user={data.user} tierCounts={data.tierCounts} />

      <section className="discovery-recommend-header">
        <div className="discovery-recommend-title-group">
          <p className="discovery-recommend-eyebrow">
            오늘의 추천 <span className="discovery-hero-dot">·</span> {data.recommendedAt}
          </p>
          <h1 className="discovery-recommend-title">
            당신을 위한 <em>{data.totalCount}개</em> 종목
          </h1>
        </div>
        <div className="discovery-recommend-controls">
          <p className="discovery-recommend-desc">
            AI가 발굴한 종목을 추천 사유와 함께 확인하세요.
          </p>
          <FilterChips active={activeFilter} onChange={setActiveFilter} />
        </div>
      </section>

      <div className="discovery-tier-list">
        {filteredTiers.map((t) => (
          <TierSection key={t.tier} tierData={t} />
        ))}
        {filteredTiers.length === 0 && (
          <div className="discovery-empty">
            <p>해당 필터에 맞는 종목이 없습니다.</p>
            <button
              type="button"
              className="discovery-empty-reset"
              onClick={() => setActiveFilter('ALL')}
            >
              전체 보기
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
