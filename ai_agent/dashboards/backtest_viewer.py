"""MODU backtest 결과 시각화 대시보드.

실행:
    streamlit run dashboards/backtest_viewer.py

입력 데이터 (사이드바에서 path 선택):
  1. decisions JSONL — replay_runner 출력
  2. scored JSONL    — score_with_post_mortem 출력 (선택)

scored JSONL이 있으면 Calibration / PnL / Reasoning 탭이 완전 동작.
없으면 decisions만으로 가능한 부분(Overview, Quality, Comparison)만 활성.
"""
from __future__ import annotations

import json
import math
import os
import subprocess
import sys
import time
from collections import Counter
from datetime import date, datetime
from pathlib import Path
from typing import Any

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import streamlit as st

# ============================================================
# 디자인 상수 — 모드별 색 + 차트 default
# ============================================================

# 모드별 일관 색 (모든 탭에서 동일 색)
MODE_COLORS = {
    "A": "#2563EB",       # blue — Bull/Bear 토론 (MVP)
    "B": "#F59E0B",       # amber — 단일 에이전트 ablation
    "random": "#94A3B8",  # slate — baseline
    "mock": "#10B981",    # emerald — 룰 stub
    "DA": "#8B5CF6",      # violet — DA framework default
}

ACTION_COLORS = {
    "buy": "#16A34A",     # green
    "sell": "#DC2626",    # red
    "hold": "#94A3B8",    # slate
    "other": "#A1A1AA",
}


# 종목코드 → 종목명 매핑 (KOSPI/KOSDAQ 주요 30개 fallback).
# ai_agent/backtest/data/stock_master.csv가 있으면 그게 우선.
_FALLBACK_STOCK_NAMES: dict[str, str] = {
    "005930": "삼성전자", "000660": "SK하이닉스", "035420": "NAVER",
    "035720": "카카오", "051910": "LG화학", "068270": "셀트리온",
    "005380": "현대차", "000270": "기아", "105560": "KB금융",
    "055550": "신한지주", "017670": "SK텔레콤", "015760": "한국전력",
    "032830": "삼성생명", "009150": "삼성전기", "066570": "LG전자",
    "323410": "카카오뱅크", "329180": "현대중공업", "003670": "포스코퓨처엠",
    "034730": "SK", "012330": "현대모비스", "207940": "삼성바이오로직스",
    "006400": "삼성SDI", "028260": "삼성물산", "010130": "고려아연",
    "086790": "하나금융지주", "316140": "우리금융지주", "024110": "기업은행",
    "096770": "SK이노베이션", "018260": "삼성에스디에스", "267260": "HD현대일렉트릭",
}


@st.cache_data(show_spinner=False)
def _load_stock_names() -> dict[str, str]:
    """stock_master.csv가 있으면 우선 로드, 없으면 fallback dict."""
    csv = Path(__file__).resolve().parent.parent / "backtest" / "data" / "stock_master.csv"
    if csv.exists():
        try:
            df = pd.read_csv(csv, dtype={"stock_code": str})
            names = dict(zip(df["stock_code"], df["stock_name"]))
            # fallback과 병합 — CSV에 없는 종목은 fallback 사용
            return {**_FALLBACK_STOCK_NAMES, **names}
        except Exception:
            pass
    return _FALLBACK_STOCK_NAMES


def label_stock(code: str | None) -> str:
    """종목코드 → '005930 삼성전자' 형식. 이름 없으면 코드만."""
    if not code:
        return "?"
    names = _load_stock_names()
    name = names.get(str(code))
    return f"{code} {name}" if name else str(code)


def _run_dir_label(run_dir: Path) -> str:
    """run 디렉터리 → '2024-01-02 ~ 2024-01-15 · 8 files' 형식.

    날짜 추출 우선순위:
      1. summary_<start>_<end>_*.json 파일명 파싱
      2. triggers_*.jsonl 또는 scored_*.jsonl 의 min/max 날짜
      3. 둘 다 실패 시 폴더명
    """
    files = list(run_dir.glob("*.jsonl"))
    n_files = len(files)

    # summary 파일에서 추출 시도
    for s in run_dir.glob("summary_*.json"):
        # 형식: summary_<YYYY-MM-DD>_<YYYY-MM-DD>_<user>_<hash>.json
        parts = s.stem.split("_")
        if len(parts) >= 3:
            try:
                from datetime import date as _d
                start = _d.fromisoformat(parts[1])
                end = _d.fromisoformat(parts[2])
                return f"{start.isoformat()} ~ {end.isoformat()} · {n_files} files"
            except (ValueError, IndexError):
                pass

    # triggers/scored 파일명에서 추출
    date_files = [
        f for f in files
        if f.stem.startswith(("triggers_", "scored_"))
    ]
    if date_files:
        try:
            dates = [f.stem.split("_", 1)[1] for f in date_files]
            return f"{min(dates)} ~ {max(dates)} · {n_files} files"
        except (ValueError, IndexError):
            pass

    # fallback: 폴더명
    return f"{run_dir.name} · {n_files} files"


def _apply_chart_style(fig: go.Figure, height: int = 420) -> go.Figure:
    """모든 차트에 적용할 공통 스타일 — 마진 / 폰트 / 그리드 / 호버."""
    fig.update_layout(
        height=height,
        margin=dict(l=40, r=20, t=40, b=40),
        font=dict(family="Pretendard, -apple-system, sans-serif", size=13),
        hoverlabel=dict(bgcolor="white", font_size=12),
        legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1),
        plot_bgcolor="rgba(0,0,0,0)",
        paper_bgcolor="rgba(0,0,0,0)",
    )
    fig.update_xaxes(showgrid=True, gridcolor="rgba(128,128,128,0.15)", zeroline=False)
    fig.update_yaxes(showgrid=True, gridcolor="rgba(128,128,128,0.15)", zeroline=False)
    return fig


def _color_for_mode(mode: str) -> str:
    return MODE_COLORS.get(mode, "#64748B")


def _hex_to_rgba(hex_str: str, alpha: float = 0.2) -> str:
    """'#2563EB' → 'rgba(37, 99, 235, 0.2)'. Drawdown fill에 사용."""
    h = hex_str.lstrip("#")
    if len(h) != 6:
        return f"rgba(100, 116, 139, {alpha})"
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return f"rgba({r}, {g}, {b}, {alpha})"


# ============================================================
# Postgres OHLCV 연결 — 거래 차트 탭용
# ============================================================


@st.cache_resource(show_spinner=False)
def _get_db_engine():
    """daily_ohlcv 조회용 Postgres engine. .env 자동 로드."""
    import os
    try:
        from dotenv import load_dotenv
        from sqlalchemy import create_engine
    except ImportError:
        return None

    here = Path(__file__).resolve()
    for env_path in (here.parents[2] / ".env", here.parents[1] / ".env"):
        if env_path.exists():
            load_dotenv(env_path, override=False)

    dsn = os.getenv("DATABASE_URL")
    if not dsn:
        # DB_* 변수로 합성
        host = os.getenv("DB_HOST", "localhost")
        port = os.getenv("DB_PORT", "5432")
        name = os.getenv("DB_NAME")
        user = os.getenv("DB_USERNAME")
        pw = os.getenv("DB_PASSWORD", "")
        if not (host and name and user):
            return None
        from urllib.parse import quote_plus
        auth = quote_plus(user) + (":" + quote_plus(pw) if pw else "")
        dsn = f"postgresql+psycopg2://{auth}@{host}:{port}/{name}"

    try:
        return create_engine(dsn, pool_pre_ping=True)
    except Exception:
        return None


@st.cache_data(ttl=600, show_spinner=False)
def fetch_ohlcv(stock_code: str, start: str, end: str) -> pd.DataFrame:
    """Postgres daily_ohlcv 조회. start/end는 YYYY-MM-DD string (캐시 키 안정)."""
    engine = _get_db_engine()
    if engine is None:
        return pd.DataFrame()
    from sqlalchemy import text
    try:
        df = pd.read_sql(
            text("""
                SELECT date, open, high, low, close, volume
                FROM daily_ohlcv
                WHERE stock_code = :s AND date >= :start AND date <= :end
                ORDER BY date
            """),
            engine,
            params={"s": stock_code, "start": start, "end": end},
        )
    except Exception:
        return pd.DataFrame()
    if "date" in df.columns:
        df["date"] = pd.to_datetime(df["date"])
    return df


# ============================================================
# Data loading
# ============================================================


@st.cache_data(show_spinner=False)
def load_jsonl(path: str) -> pd.DataFrame:
    """JSONL 단일 파일을 DataFrame으로. 두 포맷 자동 정규화."""
    rows: list[dict[str, Any]] = []
    p = Path(path)
    if not p.exists():
        return pd.DataFrame()
    with p.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return _finalize_df(rows)


@st.cache_data(show_spinner=False, ttl=30)
def load_run_dir(dir_path: str, prefer_scored: bool = True) -> pd.DataFrame:
    """디렉터리 내 모든 JSONL 통합 로드 — 1년치 한 번에.

    prefer_scored=True면 scored_*.jsonl 우선. 같은 날짜에 둘 다 있으면 scored 채택
    (raw_return + post_mortem 포함).

    cache_data ttl=30초 — backtest 진행 중에도 30초마다 새 데이터 자동 반영.
    """
    rows: list[dict[str, Any]] = []
    d = Path(dir_path)
    if not d.exists() or not d.is_dir():
        return pd.DataFrame()

    scored_files = sorted(d.glob("scored_*.jsonl"))
    trigger_files = sorted(d.glob("triggers_*.jsonl"))

    if prefer_scored and scored_files:
        scored_dates = {p.stem.replace("scored_", "") for p in scored_files}
        files = list(scored_files)
        # scored 없는 날짜만 trigger 추가
        for tp in trigger_files:
            if tp.stem.replace("triggers_", "") not in scored_dates:
                files.append(tp)
    else:
        files = trigger_files

    for fp in sorted(files):
        try:
            with fp.open("r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        continue
        except OSError:
            continue

    return _finalize_df(rows)


def _finalize_df(rows: list[dict[str, Any]]) -> pd.DataFrame:
    """공통 후처리: DA 포맷 정규화 + 필수 컬럼 보장.

    어떤 JSONL 포맷이 들어와도 dashboard 모든 탭이 가정하는 컬럼이 존재하도록 보장:
      - date (datetime)
      - mode
      - event_id (paired matching 용도)
    """
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    if "as_of_date" in df.columns and "date" not in df.columns:
        df = _normalize_da_format(df)
    if "date" in df.columns:
        df["date"] = pd.to_datetime(df["date"], errors="coerce")

    # mode 보장
    if "mode" not in df.columns:
        df["mode"] = "default"
    else:
        df["mode"] = df["mode"].fillna("default")

    # event_id 보장 (paired 비교 + tab_reasoning에 필수)
    if "event_id" not in df.columns:
        df["event_id"] = _synthesize_event_ids(df)
    else:
        # 일부만 비어있을 수도 있음 → fillna
        df["event_id"] = df["event_id"].where(
            df["event_id"].notna() & (df["event_id"] != ""),
            _synthesize_event_ids(df),
        )

    return df


def _synthesize_event_ids(df: pd.DataFrame) -> pd.Series:
    """event_id 합성: stock_code + date + rule_ids + 행 index."""
    def _one(row):
        stock = row.get("stock_code", "?")
        date_str = row.get("date")
        if isinstance(date_str, pd.Timestamp):
            date_str = date_str.strftime("%Y-%m-%d")
        elif date_str is None:
            date_str = row.get("as_of_date", "?")
        rules = row.get("rule_ids") or []
        if not isinstance(rules, (list, tuple)):
            rules = [str(rules)]
        return f"{stock}_{date_str}_{','.join(str(r) for r in rules)}_{row.name}"

    return df.apply(_one, axis=1)


def _normalize_da_format(df: pd.DataFrame) -> pd.DataFrame:
    """DA framework output을 우리 dashboard 스키마로 매핑.

    DA 포맷                  → 우리 dashboard 컬럼
    ─────────────────────────────────────────────
    as_of_date              → date
    decision.action         → action / side (변환)
    decision.order_amount   → order_amount
    decision.target_price   → target_price
    decision.stop_loss_price→ stop_loss_price
    decision.confidence     → confidence
    decision.extras.*       → bull_claim / bear_claim / winning_side
    close_price             → execution_price (fill 없을 때 fallback)
    fill.fill_price         → execution_price (체결 시 우선)
    rule_ids                → rule_ids
    rule_reasons            → trigger_reason
    """
    out = df.copy()
    out["date"] = out["as_of_date"]
    # DA framework output에는 mode 컬럼이 없음 — 명시적으로 보장
    if "mode" not in out.columns:
        out["mode"] = "DA"
    else:
        out["mode"] = out["mode"].fillna("DA")

    def _pluck(row, *path, default=None):
        cur = row
        for k in path:
            if isinstance(cur, dict):
                cur = cur.get(k)
            else:
                return default
        return cur if cur is not None else default

    decisions = out.get("decision")
    fills = out.get("fill")

    if decisions is not None:
        da_action = decisions.apply(lambda d: _pluck(d, "action") if isinstance(d, dict) else None)
        out["action"] = da_action.apply(lambda a: "hold" if a == "hold" else "trade")
        out["side"] = da_action.apply(lambda a: a if a in ("buy", "sell") else None)
        out["order_amount"] = decisions.apply(lambda d: _pluck(d, "order_amount"))
        out["target_price"] = decisions.apply(lambda d: _pluck(d, "target_price"))
        out["stop_loss_price"] = decisions.apply(lambda d: _pluck(d, "stop_loss_price"))
        out["confidence"] = decisions.apply(lambda d: _pluck(d, "confidence"))
        out["judgment_reason"] = decisions.apply(lambda d: _pluck(d, "reasoning"))
        out["winning_side"] = decisions.apply(lambda d: _pluck(d, "extras", "winning_side"))
        out["bull_claim"] = decisions.apply(lambda d: _pluck(d, "extras", "bull_claim"))
        out["bear_claim"] = decisions.apply(lambda d: _pluck(d, "extras", "bear_claim"))

    if fills is not None:
        fill_price = fills.apply(lambda f: _pluck(f, "fill_price") if isinstance(f, dict) else None)
        close_price = out.get("close_price", pd.Series([None] * len(out)))
        out["execution_price"] = fill_price.where(fill_price.notna(), close_price)

    if "rule_reasons" in out.columns and "trigger_reason" not in out.columns:
        out["trigger_reason"] = out["rule_reasons"]

    if "event_id" not in out.columns:
        # paired 비교용 동일 trigger 식별자: stock_code + as_of_date + rule_ids
        out["event_id"] = out.apply(
            lambda r: f"{r.get('stock_code')}_{r.get('as_of_date')}_{','.join(r.get('rule_ids', []) or [])}",
            axis=1,
        )

    return out


def detect_scored(df: pd.DataFrame) -> bool:
    """scored JSONL 여부 = raw_return / post_mortem 컬럼 존재."""
    return "raw_return" in df.columns or "post_mortem" in df.columns


# ============================================================
# Filters
# ============================================================


def sidebar_filters(df: pd.DataFrame) -> pd.DataFrame:
    """사이드바에서 모드/종목/기간 필터 받아 DataFrame을 좁힌다."""
    st.sidebar.markdown("### 필터")

    modes = sorted(df["mode"].dropna().unique()) if "mode" in df.columns else []
    selected_modes = st.sidebar.multiselect("모드", modes, default=modes)

    stocks = sorted(df["stock_code"].dropna().unique()) if "stock_code" in df.columns else []
    selected_stocks = st.sidebar.multiselect("종목", stocks, default=stocks)

    if "date" in df.columns and df["date"].notna().any():
        min_d, max_d = df["date"].min(), df["date"].max()
        date_range = st.sidebar.date_input(
            "기간",
            value=(min_d.date(), max_d.date()),
            min_value=min_d.date(),
            max_value=max_d.date(),
        )
    else:
        date_range = None

    result = df.copy()
    if selected_modes:
        result = result[result["mode"].isin(selected_modes)]
    if selected_stocks:
        result = result[result["stock_code"].isin(selected_stocks)]
    if date_range and isinstance(date_range, tuple) and len(date_range) == 2:
        start, end = date_range
        result = result[(result["date"] >= pd.Timestamp(start)) & (result["date"] <= pd.Timestamp(end))]
    return result


# ============================================================
# Tab: Overview
# ============================================================


def tab_overview(df: pd.DataFrame) -> None:
    st.markdown("### 개요")
    if df.empty:
        st.warning("선택된 필터에 매칭되는 결정이 없습니다.")
        return

    n_total = len(df)
    actions = df["action"].fillna("missing") if "action" in df.columns else pd.Series([])
    sides = df["side"].fillna("missing") if "side" in df.columns else pd.Series([])
    n_trade = (actions == "trade").sum()
    n_hold = (actions == "hold").sum()
    n_buy = ((actions == "trade") & (sides == "buy")).sum()
    n_sell = ((actions == "trade") & (sides == "sell")).sum()
    n_modes = df["mode"].nunique() if "mode" in df.columns else 1
    n_stocks = df["stock_code"].nunique() if "stock_code" in df.columns else 0

    # KPI 카드 — 6개로 확장
    c1, c2, c3, c4, c5, c6 = st.columns(6)
    c1.metric("총 결정", f"{n_total:,}")
    c2.metric("거래", f"{n_trade:,}", delta=f"{n_trade/n_total*100:.0f}%" if n_total else None)
    c3.metric("BUY", f"{n_buy:,}")
    c4.metric("SELL", f"{n_sell:,}")
    c5.metric("HOLD", f"{n_hold:,}")
    c6.metric("종목·모드", f"{n_stocks} · {n_modes}")

    st.divider()

    # 좌우 2열 — 결정 분포 + 시간별
    col_left, col_right = st.columns([1, 2])

    with col_left:
        st.markdown("#### 결정 분포")
        dist_df = pd.DataFrame({
            "category": ["BUY", "SELL", "HOLD", "other"],
            "count": [n_buy, n_sell, n_hold, n_total - n_buy - n_sell - n_hold],
        })
        dist_df = dist_df[dist_df["count"] > 0]
        fig = px.pie(
            dist_df, names="category", values="count", hole=0.55,
            color="category",
            color_discrete_map={
                "BUY": ACTION_COLORS["buy"], "SELL": ACTION_COLORS["sell"],
                "HOLD": ACTION_COLORS["hold"], "other": ACTION_COLORS["other"],
            },
        )
        fig.update_traces(textposition="outside", textinfo="label+percent")
        _apply_chart_style(fig, height=380)
        fig.update_layout(showlegend=False)
        st.plotly_chart(fig, use_container_width=True)

    with col_right:
        if "date" in df.columns and df["date"].notna().any():
            st.markdown("#### 시간별 결정 수")
            timeline = df.groupby([df["date"].dt.date, "mode"]).size().reset_index(name="count")
            timeline.columns = ["date", "mode", "count"]
            fig = px.bar(
                timeline, x="date", y="count", color="mode", barmode="group",
                color_discrete_map=MODE_COLORS,
            )
            fig.update_xaxes(title="")
            fig.update_yaxes(title="결정 수")
            _apply_chart_style(fig, height=380)
            st.plotly_chart(fig, use_container_width=True)


# ============================================================
# Tab: Quality (hit_rate)
# ============================================================


def _compute_hit_rate(group: pd.DataFrame) -> dict:
    """raw_return > 0인 거래 비율. scored JSONL 전제. 없으면 빈 dict."""
    if "raw_return" not in group.columns:
        return {"hit_rate": None, "n_trades": 0, "n_holds": 0}
    trades = group[group["raw_return"].notna()]
    holds = len(group) - len(trades)
    if len(trades) == 0:
        return {"hit_rate": None, "n_trades": 0, "n_holds": holds}
    hits = (trades["raw_return"] > 0).sum()
    return {
        "hit_rate": float(hits) / len(trades),
        "n_trades": len(trades),
        "n_holds": holds,
    }


def tab_quality(df: pd.DataFrame, scored: bool) -> None:
    st.markdown("### 결정 품질 (Hit Rate)")
    if not scored:
        st.info("📊 scored JSONL이 없어 hit_rate 계산 불가. `--score-after`로 생성된 scored_*.jsonl을 선택하세요.")
        return
    if df.empty:
        st.warning("데이터 없음.")
        return

    by_mode = (
        df.groupby("mode", dropna=False)
        .apply(_compute_hit_rate)
        .apply(pd.Series)
        .reset_index()
    )
    by_mode["hit_rate"] = by_mode["hit_rate"].fillna(0.0)

    # KPI 카드 — 모드별 hit rate
    if not by_mode.empty:
        cols = st.columns(min(len(by_mode), 4))
        for i, (_, row) in enumerate(by_mode.iterrows()):
            cols[i % len(cols)].metric(
                f"Mode {row['mode']} hit rate",
                f"{row['hit_rate']:.1%}",
                delta=f"{row['n_trades']} trades · {row['n_holds']} holds",
                delta_color="off",
            )

    st.divider()

    col_left, col_right = st.columns(2)

    with col_left:
        st.markdown("#### 모드별 hit rate")
        fig = px.bar(
            by_mode, x="mode", y="hit_rate", text="hit_rate", color="mode",
            color_discrete_map=MODE_COLORS,
        )
        fig.update_traces(texttemplate="%{text:.1%}", textposition="outside")
        fig.update_yaxes(range=[0, 1.05], tickformat=".0%", title="Hit Rate")
        fig.update_xaxes(title="")
        _apply_chart_style(fig, height=380)
        fig.update_layout(showlegend=False)
        st.plotly_chart(fig, use_container_width=True)

    with col_right:
        st.markdown("#### 분기별 hit rate (memory 누적 효과)")
        if "date" in df.columns:
            df_q = df.copy()
            df_q["quarter"] = df_q["date"].dt.to_period("Q").astype(str)
            by_q = (
                df_q.groupby(["quarter", "mode"], dropna=False)
                .apply(_compute_hit_rate)
                .apply(pd.Series)
                .reset_index()
            )
            by_q["hit_rate"] = by_q["hit_rate"].fillna(0.0)
            fig = px.line(
                by_q, x="quarter", y="hit_rate", color="mode", markers=True,
                color_discrete_map=MODE_COLORS,
            )
            fig.update_yaxes(range=[0, 1.05], tickformat=".0%", title="Hit Rate")
            fig.update_xaxes(title="")
            fig.update_traces(line_width=3, marker_size=10)
            _apply_chart_style(fig, height=380)
            st.plotly_chart(fig, use_container_width=True)

    with st.expander("모드별 상세 표"):
        st.dataframe(by_mode, use_container_width=True, hide_index=True)


# ============================================================
# Tab: PnL
# ============================================================


def tab_pnl(df: pd.DataFrame, scored: bool, equity_df: pd.DataFrame | None = None) -> None:
    st.markdown("### 투자 결과 (PnL)")
    if not scored:
        st.info("📊 scored JSONL이 없어 PnL 분석 불가.")
        return
    trades = df[df["raw_return"].notna()].copy() if "raw_return" in df.columns else pd.DataFrame()
    if trades.empty:
        st.warning("거래 결정이 없음.")
        return

    # 진짜 자산 추이 (equity_curve.jsonl) 있으면 우선 — 더 정확
    if equity_df is not None and not equity_df.empty:
        _render_equity_section(equity_df)
        st.divider()

    by_mode_summary = (
        trades.groupby("mode")
        .agg(
            n=("raw_return", "count"),
            win_rate=("raw_return", lambda s: (s > 0).mean()),
            avg_return=("raw_return", "mean"),
            cum_return=("raw_return", lambda s: (1 + s).prod() - 1),
            best=("raw_return", "max"),
            worst=("raw_return", "min"),
        )
        .reset_index()
    )

    # CAGR / MDD / Sharpe 계산 (mode별)
    extra_stats = _compute_extra_pnl_stats(trades)
    by_mode_summary = by_mode_summary.merge(extra_stats, on="mode", how="left")

    # KIS 스타일 4 핵심 KPI 카드 — 모드별 한 줄씩
    for _, row in by_mode_summary.iterrows():
        st.markdown(f"**Mode {row['mode']}**")
        c1, c2, c3, c4, c5, c6 = st.columns(6)
        c1.metric("총 수익률", f"{row['cum_return']:+.2%}")
        c2.metric("CAGR", f"{row.get('cagr', 0):+.2%}")
        c3.metric("Sharpe", f"{row.get('sharpe', 0):.2f}")
        c4.metric("MDD", f"{row.get('mdd', 0):.2%}")
        c5.metric("Win Rate", f"{row['win_rate']:.1%}")
        c6.metric("거래 수", f"{int(row['n']):,}")

    st.divider()

    # === 메인 차트: 자산 추이 + Drawdown subplot (KIS 스타일) ===
    st.markdown("#### 누적 수익률 + KOSPI 벤치마크")
    fig = _build_equity_chart(trades)
    st.plotly_chart(fig, use_container_width=True)

    st.divider()
    col_left, col_right = st.columns(2)

    with col_left:
        st.markdown("#### 거래별 수익률 분포")
        fig = px.histogram(
            trades, x="raw_return", color="mode", nbins=30,
            barmode="overlay", opacity=0.65,
            color_discrete_map=MODE_COLORS,
        )
        fig.add_vline(x=0, line_dash="dash", line_color="rgba(128,128,128,0.6)")
        fig.update_xaxes(tickformat=".0%", title="수익률")
        fig.update_yaxes(title="거래 수")
        _apply_chart_style(fig, height=320)
        st.plotly_chart(fig, use_container_width=True)

    with col_right:
        st.markdown("#### Win/Loss 비율")
        wl_data = []
        for mode, sub in trades.groupby("mode"):
            wl_data.append({"mode": mode, "result": "Win", "count": (sub["raw_return"] > 0).sum()})
            wl_data.append({"mode": mode, "result": "Loss", "count": (sub["raw_return"] <= 0).sum()})
        wl_df = pd.DataFrame(wl_data)
        fig = px.bar(
            wl_df, x="mode", y="count", color="result", barmode="group",
            color_discrete_map={"Win": ACTION_COLORS["buy"], "Loss": ACTION_COLORS["sell"]},
        )
        fig.update_xaxes(title="")
        fig.update_yaxes(title="거래 수")
        _apply_chart_style(fig, height=320)
        st.plotly_chart(fig, use_container_width=True)

    with st.expander("모드별 상세 표 (CAGR / MDD / Sharpe 포함)"):
        st.dataframe(by_mode_summary, use_container_width=True, hide_index=True)


@st.cache_data(show_spinner=False)
def _load_kospi() -> pd.DataFrame:
    """KOSPI/KOSDAQ 일별 종가 — fetch_kospi.py 산출물."""
    csv = Path(__file__).resolve().parent.parent / "backtest" / "data" / "kospi_daily.csv"
    if not csv.exists():
        return pd.DataFrame()
    df = pd.read_csv(csv)
    df["date"] = pd.to_datetime(df["date"])
    return df


def _render_equity_section(equity_df: pd.DataFrame) -> None:
    """SimplePortfolio.equity_curve 기반 정통 메트릭 + 차트.

    equity_df 컬럼: date, cash, unrealized, equity, holdings_count
    """
    import math
    df = equity_df.sort_values("date").copy()
    df["returns"] = df["equity"].pct_change().fillna(0)

    initial = float(df["equity"].iloc[0])
    final = float(df["equity"].iloc[-1])
    total_return = final / initial - 1

    days = (df["date"].iloc[-1] - df["date"].iloc[0]).days or 1
    years = days / 365.25
    cagr = (1 + total_return) ** (1 / years) - 1 if years > 0 else 0.0

    std = float(df["returns"].std()) or 1e-9
    sharpe = (float(df["returns"].mean()) / std) * math.sqrt(252)

    downside = df["returns"][df["returns"] < 0]
    downside_std = float(downside.std()) if len(downside) > 1 else 1e-9
    sortino = (float(df["returns"].mean()) / downside_std) * math.sqrt(252)

    peak = df["equity"].cummax()
    drawdown = (df["equity"] - peak) / peak
    mdd = float(drawdown.min())
    calmar = cagr / abs(mdd) if abs(mdd) > 1e-9 else 0.0

    st.markdown("#### 📊 정통 backtest 메트릭 (실제 portfolio 자산 추이 기반)")
    c1, c2, c3, c4, c5, c6 = st.columns(6)
    c1.metric("총 수익률", f"{total_return:+.2%}")
    c2.metric("CAGR", f"{cagr:+.2%}")
    c3.metric("Sharpe", f"{sharpe:.2f}")
    c4.metric("Sortino", f"{sortino:.2f}")
    c5.metric("MDD", f"{mdd:.2%}")
    c6.metric("Calmar", f"{calmar:.2f}")

    # 자산 추이 + Drawdown subplot
    from plotly.subplots import make_subplots
    fig = make_subplots(
        rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.06,
        row_heights=[0.7, 0.3],
        subplot_titles=("자산 추이 (cash + 보유 평가)", "Drawdown"),
    )
    fig.add_trace(
        go.Scatter(
            x=df["date"], y=df["equity"], mode="lines", name="Equity",
            line=dict(color="#2563EB", width=2.5),
            hovertemplate="<b>%{x|%Y-%m-%d}</b><br>자산 %{y:,.0f}원<extra></extra>",
        ),
        row=1, col=1,
    )
    fig.add_hline(y=initial, line_dash="dash", line_color="rgba(128,128,128,0.5)",
                  annotation_text=f"시작 {initial:,.0f}원", row=1, col=1)

    # KOSPI 벤치마크 (같은 기간 normalized)
    kospi = _load_kospi()
    if not kospi.empty:
        kp = kospi[(kospi["date"] >= df["date"].min()) & (kospi["date"] <= df["date"].max())].copy()
        if not kp.empty:
            base = float(kp["kospi_close"].iloc[0])
            kp["kospi_equity"] = kp["kospi_close"] / base * initial
            fig.add_trace(
                go.Scatter(
                    x=kp["date"], y=kp["kospi_equity"], mode="lines", name="KOSPI",
                    line=dict(color="rgba(120,120,120,0.7)", width=1.8, dash="dash"),
                    hovertemplate="<b>%{x|%Y-%m-%d}</b><br>KOSPI %{y:,.0f}<extra></extra>",
                ),
                row=1, col=1,
            )

    fig.add_trace(
        go.Scatter(
            x=df["date"], y=drawdown, mode="lines", fill="tozeroy",
            name="Drawdown",
            line=dict(color="#DC2626", width=1.5),
            fillcolor=_hex_to_rgba("#DC2626", alpha=0.2),
            showlegend=False,
            hovertemplate="<b>%{x|%Y-%m-%d}</b><br>DD %{y:.2%}<extra></extra>",
        ),
        row=2, col=1,
    )
    fig.update_yaxes(tickformat=",.0f", title="자산 (원)", row=1, col=1)
    fig.update_yaxes(tickformat=".1%", title="Drawdown", row=2, col=1)
    _apply_chart_style(fig, height=560)
    fig.update_layout(hovermode="x unified", margin=dict(l=60, r=40, t=60, b=40))
    st.plotly_chart(fig, use_container_width=True)


@st.cache_data(show_spinner=False, ttl=30)
def _load_equity_curve(run_dir_str: str) -> pd.DataFrame:
    """run_dir의 equity_curve.jsonl 로드 — 일별 mark-to-market 자산.

    각 라인: {date, cash, unrealized, equity, holdings_count}
    """
    path = Path(run_dir_str) / "equity_curve.jsonl"
    if not path.exists():
        return pd.DataFrame()
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    if "date" in df.columns:
        df["date"] = pd.to_datetime(df["date"])
    return df


def _compute_extra_pnl_stats(trades: pd.DataFrame) -> pd.DataFrame:
    """모드별 CAGR / Sharpe / MDD 계산. trades.date / raw_return 사용."""
    rows = []
    for mode, sub in trades.groupby("mode"):
        sub = sub.sort_values("date").copy()
        sub["cum"] = (1 + sub["raw_return"]).cumprod() - 1
        equity = (1 + sub["raw_return"]).cumprod()

        # CAGR
        days = (sub["date"].max() - sub["date"].min()).days or 1
        years = days / 365.25
        total = float(equity.iloc[-1] - 1)
        cagr = (1 + total) ** (1 / years) - 1 if years > 0 else 0.0

        # Sharpe (거래별 raw_return 기반, 연환산 √252)
        std = float(sub["raw_return"].std()) or 1e-9
        mean = float(sub["raw_return"].mean())
        sharpe = (mean / std) * math.sqrt(252)

        # MDD
        peak = equity.cummax()
        dd = (equity - peak) / peak
        mdd = float(dd.min())

        rows.append({"mode": mode, "cagr": cagr, "sharpe": sharpe, "mdd": mdd})
    return pd.DataFrame(rows)


def _build_equity_chart(trades: pd.DataFrame) -> go.Figure:
    """주식 차트 스타일 누적 수익률 + KOSPI 벤치마크 + BUY/SELL 마커 + Drawdown subplot."""
    from plotly.subplots import make_subplots
    fig = make_subplots(
        rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.06,
        row_heights=[0.7, 0.3],
        subplot_titles=("누적 수익률 (전략 vs KOSPI)", "Drawdown"),
    )

    trades_sorted = trades.sort_values("date").copy()

    # 1) 모드별 누적 수익률 라인
    for mode, sub in trades_sorted.groupby("mode"):
        sub = sub.sort_values("date").copy()
        sub["cum"] = (1 + sub["raw_return"]).cumprod() - 1
        color = _color_for_mode(str(mode))
        fig.add_trace(
            go.Scatter(
                x=sub["date"], y=sub["cum"], mode="lines+markers",
                name=f"Mode {mode}",
                line=dict(color=color, width=2.5),
                marker=dict(size=6, color=color),
                hovertemplate="<b>%{x|%Y-%m-%d}</b><br>누적 %{y:+.2%}<extra></extra>",
            ),
            row=1, col=1,
        )

        # BUY/SELL 마커 (삼각형)
        for side, symbol, marker_color in (("buy", "triangle-up", ACTION_COLORS["buy"]),
                                            ("sell", "triangle-down", ACTION_COLORS["sell"])):
            side_sub = sub[sub["side"] == side]
            if not side_sub.empty:
                fig.add_trace(
                    go.Scatter(
                        x=side_sub["date"], y=side_sub["cum"], mode="markers",
                        name=f"{mode} {side.upper()}",
                        marker=dict(symbol=symbol, size=14, color=marker_color,
                                    line=dict(width=1, color="white")),
                        text=side_sub.apply(
                            lambda r: f"{label_stock(r['stock_code'])}<br>"
                                      f"진입 {r.get('execution_price'):.0f}원<br>"
                                      f"수익률 {r['raw_return']:+.2%}",
                            axis=1,
                        ),
                        hovertemplate="%{text}<extra></extra>",
                        showlegend=False,
                    ),
                    row=1, col=1,
                )

        # Drawdown
        equity = (1 + sub["raw_return"]).cumprod()
        peak = equity.cummax()
        dd = (equity - peak) / peak
        fig.add_trace(
            go.Scatter(
                x=sub["date"], y=dd, mode="lines", fill="tozeroy",
                name=f"DD {mode}",
                line=dict(color=color, width=1.5),
                fillcolor=_hex_to_rgba(color, alpha=0.2),
                showlegend=False,
                hovertemplate="<b>%{x|%Y-%m-%d}</b><br>DD %{y:.2%}<extra></extra>",
            ),
            row=2, col=1,
        )

    # 2) KOSPI 벤치마크 (기간만 잘라 같은 시작점 기준 누적 수익률로 정규화)
    kospi = _load_kospi()
    if not kospi.empty:
        start_d, end_d = trades_sorted["date"].min(), trades_sorted["date"].max()
        kp = kospi[(kospi["date"] >= start_d) & (kospi["date"] <= end_d)].copy()
        if not kp.empty and "kospi_close" in kp.columns:
            base = float(kp["kospi_close"].iloc[0])
            kp["kospi_cum"] = kp["kospi_close"] / base - 1
            fig.add_trace(
                go.Scatter(
                    x=kp["date"], y=kp["kospi_cum"], mode="lines",
                    name="KOSPI",
                    line=dict(color="rgba(120,120,120,0.7)", width=1.8, dash="dash"),
                    hovertemplate="<b>%{x|%Y-%m-%d}</b><br>KOSPI %{y:+.2%}<extra></extra>",
                ),
                row=1, col=1,
            )

    fig.update_xaxes(title="", row=2, col=1)
    fig.update_yaxes(tickformat=".1%", title="누적 수익률", row=1, col=1)
    fig.update_yaxes(tickformat=".1%", title="Drawdown", row=2, col=1)
    fig.add_hline(y=0, line_dash="dot", line_color="rgba(128,128,128,0.5)", row=1, col=1)
    fig.add_hline(y=0, line_dash="dot", line_color="rgba(128,128,128,0.5)", row=2, col=1)
    _apply_chart_style(fig, height=560)
    fig.update_layout(hovermode="x unified")
    return fig


# ============================================================
# Tab: Calibration
# ============================================================


def tab_calibration(df: pd.DataFrame, scored: bool) -> None:
    st.markdown("### Confidence Calibration")
    if not scored:
        st.info("scored JSONL이 없어 Calibration 계산 불가.")
        return
    if "confidence" not in df.columns or "raw_return" not in df.columns:
        st.warning("confidence 또는 raw_return 컬럼이 없음.")
        return

    trades = df[df["raw_return"].notna() & df["confidence"].notna()].copy()
    if trades.empty:
        st.warning("계산할 결정이 없음.")
        return

    trades["hit"] = (trades["raw_return"] > 0).astype(int)
    bins = [0.0, 0.2, 0.4, 0.6, 0.8, 1.01]
    labels = ["[0.0, 0.2)", "[0.2, 0.4)", "[0.4, 0.6)", "[0.6, 0.8)", "[0.8, 1.0]"]

    rows: list[dict] = []
    for mode, sub in trades.groupby("mode"):
        sub_local = sub.copy()
        sub_local["bin"] = pd.cut(sub_local["confidence"], bins=bins, labels=labels, right=False)
        for label, bin_df in sub_local.groupby("bin"):
            if len(bin_df) == 0:
                continue
            rows.append({
                "mode": mode,
                "bin": str(label),
                "avg_confidence": float(bin_df["confidence"].mean()),
                "hit_rate": float(bin_df["hit"].mean()),
                "n": int(len(bin_df)),
            })
    if not rows:
        st.warning("bin에 들어간 표본이 없음.")
        return
    bin_df = pd.DataFrame(rows)

    # ECE per mode
    ece_rows: list[dict] = []
    for mode, sub in bin_df.groupby("mode"):
        n_total = sub["n"].sum()
        if n_total == 0:
            continue
        ece = float(((sub["avg_confidence"] - sub["hit_rate"]).abs() * sub["n"] / n_total).sum())
        ece_rows.append({"mode": mode, "ECE": ece, "n_trades": int(n_total)})
    ece_df = pd.DataFrame(ece_rows)

    # KPI — ECE 카드
    if not ece_df.empty:
        cols = st.columns(min(len(ece_df), 4))
        for i, (_, row) in enumerate(ece_df.iterrows()):
            quality = "잘 보정됨" if row["ECE"] <= 0.05 else ("보통" if row["ECE"] <= 0.10 else "과신 경향")
            cols[i % len(cols)].metric(
                f"Mode {row['mode']} ECE",
                f"{row['ECE']:.3f}",
                delta=quality,
                delta_color="off",
            )

    st.divider()

    col_left, col_right = st.columns([2, 1])

    with col_left:
        st.markdown("#### Reliability Diagram")
        fig = go.Figure()
        fig.add_trace(go.Scatter(
            x=[0, 1], y=[0, 1], mode="lines", name="perfect",
            line=dict(dash="dash", color="rgba(128,128,128,0.5)", width=2),
        ))
        for mode, sub in bin_df.groupby("mode"):
            fig.add_trace(go.Scatter(
                x=sub["avg_confidence"], y=sub["hit_rate"],
                mode="markers+lines", name=str(mode),
                line=dict(color=_color_for_mode(str(mode)), width=2.5),
                marker=dict(size=12, color=_color_for_mode(str(mode))),
                text=sub["n"].apply(lambda x: f"n={x}"),
                hovertemplate="confidence=%{x:.2f}<br>hit=%{y:.2%}<br>%{text}",
            ))
        fig.update_xaxes(range=[0, 1], tickformat=".0%", title="평균 confidence")
        fig.update_yaxes(range=[0, 1], tickformat=".0%", title="실제 hit rate")
        _apply_chart_style(fig, height=460)
        st.plotly_chart(fig, use_container_width=True)

    with col_right:
        st.markdown("#### bin별 표본 수")
        fig = px.bar(
            bin_df, x="bin", y="n", color="mode", barmode="group",
            color_discrete_map=MODE_COLORS,
        )
        fig.update_xaxes(title="confidence bin", tickangle=-30)
        fig.update_yaxes(title="결정 수")
        _apply_chart_style(fig, height=460)
        st.plotly_chart(fig, use_container_width=True)

    with st.expander("ECE 상세 + bin 표"):
        st.markdown("**ECE per mode**")
        st.dataframe(ece_df, use_container_width=True, hide_index=True)
        st.markdown("**bin별 상세**")
        st.dataframe(bin_df, use_container_width=True, hide_index=True)


# ============================================================
# Tab: Reasoning Samples
# ============================================================


def tab_reasoning(df: pd.DataFrame) -> None:
    """1년치 결정에서 날짜 → 결정 drill-down (KIS 스타일)."""
    st.markdown("### 추론 텍스트 뷰어 — 날짜별 결정 상세")
    if df.empty:
        st.warning("데이터 없음.")
        return

    # 1년치 결정 분포 (작은 timeline) — 어떤 날짜에 결정이 있는지 한눈에
    if "date" in df.columns and df["date"].notna().any():
        daily_counts = df.groupby(df["date"].dt.date).size().reset_index(name="count")
        daily_counts.columns = ["date", "count"]
        fig = px.bar(daily_counts, x="date", y="count", color_discrete_sequence=["#2563EB"])
        fig.update_xaxes(title="", showgrid=False)
        fig.update_yaxes(title="결정 수", showgrid=False)
        fig.update_traces(marker_line_width=0)
        _apply_chart_style(fig, height=140)
        st.plotly_chart(fig, use_container_width=True)

    # Step 1: 날짜 선택
    available_dates = sorted(df["date"].dt.date.dropna().unique()) if "date" in df.columns else []
    if not available_dates:
        st.warning("date 컬럼 없음.")
        return

    c_date, c_filter = st.columns([2, 3])
    with c_date:
        selected_date = st.selectbox(
            f"📅 날짜 선택 ({len(available_dates)}일치)",
            options=available_dates,
            format_func=lambda d: d.isoformat(),
        )
    day_df = df[df["date"].dt.date == selected_date]

    with c_filter:
        modes_on_day = sorted(day_df["mode"].dropna().unique()) if "mode" in day_df.columns else []
        if len(modes_on_day) > 1:
            selected_mode = st.selectbox("모드 필터", options=["전체"] + list(modes_on_day))
            if selected_mode != "전체":
                day_df = day_df[day_df["mode"] == selected_mode]

    if day_df.empty:
        st.info(f"{selected_date}에 결정이 없습니다. 다른 날짜를 선택하세요.")
        return

    # Step 2: 그날 결정 중 하나 선택
    decision_options = []
    for _, row in day_df.iterrows():
        action_label = f"{row.get('action', '?')}/{row.get('side', '-') or '-'}"
        conf = row.get("confidence")
        conf_str = f"conf {conf:.2f}" if pd.notna(conf) else ""
        label = f"{label_stock(row.get('stock_code'))} · {action_label} · {conf_str}"
        decision_options.append((label, row.get("event_id")))

    selected_label = st.selectbox(
        f"결정 선택 ({len(decision_options)}건)",
        options=[lbl for lbl, _ in decision_options],
    )
    matching = [eid for lbl, eid in decision_options if lbl == selected_label]
    if not matching:
        return
    row = day_df[day_df["event_id"] == matching[0]].iloc[0]

    st.divider()

    # KPI 카드
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Decision", f"{row.get('action') or '-'} / {row.get('side') or '-'}")
    c2.metric("Confidence", f"{row.get('confidence', 0):.2f}" if pd.notna(row.get("confidence")) else "-")
    c3.metric("Winning side", str(row.get("winning_side") or "-"))
    if pd.notna(row.get("raw_return")):
        c4.metric("Raw return", f"{row['raw_return']:+.2%}")
    else:
        oa = row.get("order_amount")
        oa_int = int(oa) if pd.notna(oa) else 0
        c4.metric("Order amount", f"{oa_int:,}")

    # 추론 텍스트 — 2열로
    st.divider()
    left, right = st.columns(2)

    with left:
        if pd.notna(row.get("judgment_reason")) and row.get("judgment_reason"):
            st.markdown("##### 📝 판단 사유")
            st.write(row["judgment_reason"])
        if pd.notna(row.get("bull_claim")) and row.get("bull_claim"):
            st.markdown("##### 🐂 Bull 주장")
            st.write(row["bull_claim"])
        if pd.notna(row.get("bear_claim")) and row.get("bear_claim"):
            st.markdown("##### 🐻 Bear 주장")
            st.write(row["bear_claim"])

    with right:
        pm = row.get("post_mortem")
        if isinstance(pm, dict):
            st.markdown("##### 🔍 사후 회고 (Post Mortem)")
            if pm.get("summary"):
                st.info(pm["summary"])
            for key, ko in [
                ("entry_timing_assessment", "진입 시점"),
                ("exit_rule_assessment", "익절/손절"),
                ("risk_prediction_accuracy", "리스크 예측"),
            ]:
                if pm.get(key):
                    st.markdown(f"**{ko}**: {pm[key]}")
            if pm.get("missed_signals"):
                st.markdown("**놓친 신호**")
                st.write("\n".join(f"• {s}" for s in pm["missed_signals"]))
            if pm.get("lessons"):
                st.markdown("**교훈**")
                st.write("\n".join(f"• {s}" for s in pm["lessons"]))
        else:
            st.markdown("##### 🔍 사후 회고")
            st.caption("post_mortem 없음 (HOLD 결정이거나 --score-after 안 함)")

    # 메타 정보
    with st.expander("Raw 메타 정보"):
        st.json({
            "event_id": row.get("event_id"),
            "stock_code": row.get("stock_code"),
            "rule_ids": row.get("rule_ids"),
            "trigger_reason": row.get("trigger_reason"),
            "execution_price": row.get("execution_price"),
            "target_price": row.get("target_price"),
            "stop_loss_price": row.get("stop_loss_price"),
            "exit_price": row.get("exit_price"),
            "holding_days": row.get("holding_days"),
        })


# ============================================================
# Tab: 거래 차트 (캔들스틱 + 매매 마커 + 거래량)
# ============================================================


def tab_trade_chart(df: pd.DataFrame) -> None:
    """종목별 실 주식 차트 위에 우리 매매 시점을 표시 — 비전문가 친화."""
    st.markdown("### 📈 거래 차트 — 실제 주가 위에 AI 매매 시점 표시")
    st.caption(
        "AI 에이전트가 **언제** **어떤 가격**에 매수/매도 추천을 냈는지 실제 주가 차트 위에 표시합니다.  \n"
        "🟢 위쪽 화살표 = 매수 추천  ·  🔴 아래쪽 화살표 = 매도 추천  ·  점선 영역 = AI가 설정한 목표가(녹색) / 손절가(빨강)"
    )

    if df.empty or "stock_code" not in df.columns:
        st.warning("데이터 없음.")
        return

    stocks = sorted(s for s in df["stock_code"].dropna().unique() if s)
    if not stocks:
        st.warning("종목 정보 없음.")
        return

    c_stock, c_style = st.columns([2, 1])
    with c_stock:
        selected = st.selectbox(
            "종목 선택", options=stocks, format_func=label_stock, key="trade_chart_stock"
        )
    with c_style:
        chart_style = st.radio(
            "차트 스타일",
            options=["가격선 (단순)", "캔들봉 (전문가용)"],
            index=0,
            horizontal=True,
        )

    stock_df = df[df["stock_code"] == selected].copy()
    if stock_df.empty:
        st.info("이 종목 거래 없음.")
        return

    # 기간 — 거래 전후 5일 padding
    pad = pd.Timedelta(days=5)
    start = (stock_df["date"].min() - pad).date()
    end = (stock_df["date"].max() + pad).date()

    ohlcv = fetch_ohlcv(selected, start.isoformat(), end.isoformat())
    if ohlcv.empty:
        st.warning(
            f"DB에서 {selected} {start}~{end} 가격 데이터를 가져올 수 없습니다."
        )
        return

    # 종목 거래 요약 KPI
    trades_only = stock_df[stock_df["side"].isin(["buy", "sell"])]
    n_buy = (trades_only["side"] == "buy").sum()
    n_sell = (trades_only["side"] == "sell").sum()
    n_hold = (stock_df["action"] == "hold").sum()
    avg_ret = trades_only["raw_return"].mean() if "raw_return" in trades_only.columns and not trades_only.empty else 0.0

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("매수 추천", f"{n_buy}건")
    c2.metric("매도 추천", f"{n_sell}건")
    c3.metric("관망(HOLD)", f"{n_hold}건")
    c4.metric("평균 수익률", f"{avg_ret:+.2%}" if pd.notna(avg_ret) else "-")

    # === 차트 ===
    from plotly.subplots import make_subplots

    fig = make_subplots(
        rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.04,
        row_heights=[0.78, 0.22],
        subplot_titles=(f"{label_stock(selected)} 주가", "거래량"),
    )

    # 1) 가격 — line 또는 캔들
    if chart_style.startswith("가격선"):
        fig.add_trace(
            go.Scatter(
                x=ohlcv["date"], y=ohlcv["close"],
                mode="lines", name="종가",
                line=dict(color="#1F2937", width=2),
                hovertemplate="<b>%{x|%Y-%m-%d}</b><br>종가 %{y:,.0f}원<extra></extra>",
            ),
            row=1, col=1,
        )
    else:
        fig.add_trace(
            go.Candlestick(
                x=ohlcv["date"],
                open=ohlcv["open"], high=ohlcv["high"],
                low=ohlcv["low"], close=ohlcv["close"],
                increasing_line_color="#DC2626",
                decreasing_line_color="#2563EB",
                name="OHLC",
                showlegend=False,
            ),
            row=1, col=1,
        )

    # 2) 매수/매도 시점 마커 + annotation (한글)
    marker_specs = [
        ("buy",  "triangle-up",   ACTION_COLORS["buy"],  "🟢 AI 매수"),
        ("sell", "triangle-down", ACTION_COLORS["sell"], "🔴 AI 매도"),
    ]
    close_lookup = dict(zip(ohlcv["date"], ohlcv["close"]))
    for side, symbol, color, legend in marker_specs:
        side_df = stock_df[stock_df["side"] == side].copy()
        if side_df.empty:
            continue
        side_df["marker_y"] = side_df["execution_price"]
        side_df["marker_y"] = side_df["marker_y"].fillna(
            side_df["date"].map(close_lookup)
        )

        def _hover(r):
            parts = [f"<b>{legend} — {label_stock(selected)}</b>"]
            parts.append(f"날짜: {r.get('date').strftime('%Y-%m-%d') if pd.notna(r.get('date')) else ''}")
            if pd.notna(r.get("execution_price")):
                parts.append(f"매매가: {float(r['execution_price']):,.0f}원")
            if pd.notna(r.get("target_price")):
                parts.append(f"목표가: {float(r['target_price']):,.0f}원")
            if pd.notna(r.get("stop_loss_price")):
                parts.append(f"손절가: {float(r['stop_loss_price']):,.0f}원")
            if pd.notna(r.get("raw_return")):
                parts.append(f"7일 후 수익률: {r['raw_return']:+.2%}")
            if pd.notna(r.get("confidence")):
                parts.append(f"AI 자신감: {r['confidence']:.0%}")
            return "<br>".join(parts)

        fig.add_trace(
            go.Scatter(
                x=side_df["date"], y=side_df["marker_y"],
                mode="markers+text",
                marker=dict(symbol=symbol, size=20, color=color,
                            line=dict(width=2, color="white")),
                name=legend,
                text=["매수" if side == "buy" else "매도"] * len(side_df),
                textposition="top center" if side == "buy" else "bottom center",
                textfont=dict(size=11, color=color),
                hovertext=side_df.apply(_hover, axis=1),
                hovertemplate="%{hovertext}<extra></extra>",
            ),
            row=1, col=1,
        )

    # 3) target / stop 가로 점선 — line 자체만, annotation은 차트 내부 top에 배치
    # (외부 right로 두면 잘림 — 마진 안 보장)
    has_trade = stock_df[stock_df["side"].isin(["buy", "sell"])].copy()
    seen_targets: set[float] = set()  # 같은 가격 중복 표시 방지
    seen_stops: set[float] = set()
    for _, r in has_trade.iterrows():
        tp = r.get("target_price")
        if pd.notna(tp) and float(tp) not in seen_targets:
            seen_targets.add(float(tp))
            fig.add_hline(
                y=float(tp),
                line=dict(color=ACTION_COLORS["buy"], dash="dot", width=1.2),
                row=1, col=1, opacity=0.5,
                annotation_text=f"🎯 목표 {float(tp):,.0f}",
                annotation_position="top left",
                annotation_font=dict(size=10, color=ACTION_COLORS["buy"]),
                annotation_xanchor="left",
                annotation_x=0.01,
            )
        sp = r.get("stop_loss_price")
        if pd.notna(sp) and float(sp) not in seen_stops:
            seen_stops.add(float(sp))
            fig.add_hline(
                y=float(sp),
                line=dict(color=ACTION_COLORS["sell"], dash="dot", width=1.2),
                row=1, col=1, opacity=0.5,
                annotation_text=f"⚠️ 손절 {float(sp):,.0f}",
                annotation_position="bottom left",
                annotation_font=dict(size=10, color=ACTION_COLORS["sell"]),
                annotation_xanchor="left",
                annotation_x=0.01,
            )

    # 4) 거래량 막대 (상승봉=빨강, 하락봉=파랑)
    ohlcv_v = ohlcv.copy()
    ohlcv_v["color"] = (ohlcv_v["close"] >= ohlcv_v["open"]).map(
        {True: "#DC2626", False: "#2563EB"}
    )
    fig.add_trace(
        go.Bar(
            x=ohlcv_v["date"], y=ohlcv_v["volume"],
            marker_color=ohlcv_v["color"], showlegend=False, opacity=0.6,
            hovertemplate="%{x|%Y-%m-%d}<br>거래량 %{y:,.0f}<extra></extra>",
        ),
        row=2, col=1,
    )

    fig.update_layout(xaxis_rangeslider_visible=False)
    fig.update_yaxes(title="가격 (원)", row=1, col=1, tickformat=",.0f")
    fig.update_yaxes(title="거래량", row=2, col=1)
    _apply_chart_style(fig, height=640)
    fig.update_layout(
        hovermode="x unified",
        margin=dict(l=60, r=80, t=60, b=40),   # 우측 여백 확보
    )
    st.plotly_chart(fig, use_container_width=True)

    # === 한글 설명 ===
    with st.expander("📖 차트 보는 법"):
        st.markdown("""
        - **검은색 가격선**: 실제 주식 종가의 시간 흐름
        - **🟢 매수 (위쪽 화살표)**: AI가 "이 시점에 사라"고 추천한 시각과 가격
        - **🔴 매도 (아래쪽 화살표)**: AI가 "팔라"고 추천한 시각과 가격
        - **🎯 목표가 (녹색 점선)**: 매수 시 AI가 설정한 익절 가격 — 여기 도달하면 익절
        - **⚠️ 손절가 (빨강 점선)**: AI가 설정한 손실 한도 — 여기 떨어지면 손절
        - **하단 막대**: 거래량. 색은 그날 상승(빨강) / 하락(파랑)
        - 마커에 마우스 올리면 그 결정의 모든 정보 (목표/손절/수익률/자신감)
        """)

    # === 거래 내역 표 (한글) ===
    st.markdown("##### 📋 이 종목의 AI 매매 기록")
    rename_map = {
        "date": "날짜", "side": "매매", "execution_price": "체결가",
        "target_price": "목표가", "stop_loss_price": "손절가",
        "exit_price": "청산가", "raw_return": "수익률",
        "confidence": "자신감", "winning_side": "토론 우세",
    }
    show_cols = [c for c in rename_map if c in stock_df.columns]
    summary = stock_df[show_cols].copy().sort_values("date").reset_index(drop=True)
    summary = summary.rename(columns=rename_map)
    # 매매 한글화
    if "매매" in summary.columns:
        summary["매매"] = summary["매매"].map(
            {"buy": "🟢 매수", "sell": "🔴 매도"}
        ).fillna("⚪ 관망")
    # 수익률 % 포맷
    if "수익률" in summary.columns:
        summary["수익률"] = summary["수익률"].apply(
            lambda x: f"{x:+.2%}" if pd.notna(x) else "-"
        )
    if "자신감" in summary.columns:
        summary["자신감"] = summary["자신감"].apply(
            lambda x: f"{x:.0%}" if pd.notna(x) else "-"
        )
    st.dataframe(summary, use_container_width=True, hide_index=True)


# ============================================================
# Tab: Comparison (paired)
# ============================================================


def tab_comparison(df: pd.DataFrame, scored: bool) -> None:
    st.markdown("### 모드 paired 비교")
    if df.empty or "event_id" not in df.columns:
        st.warning("event_id가 없거나 데이터가 비어있음.")
        return

    modes = sorted(df["mode"].dropna().unique())
    if len(modes) < 2:
        st.info("모드가 2개 이상이어야 비교 가능 (현재 모드 목록: " + str(modes) + ")")
        return
    c1, c2 = st.columns(2)
    a = c1.selectbox("Mode A", modes, index=0)
    b = c2.selectbox("Mode B", modes, index=1 if len(modes) > 1 else 0)

    da = df[df["mode"] == a].set_index("event_id")
    db = df[df["mode"] == b].set_index("event_id")
    common = sorted(set(da.index) & set(db.index))
    if not common:
        st.warning("두 모드 간 공통 event_id가 없음.")
        return

    # 결정 일치율
    agree = 0
    for eid in common:
        if da.loc[eid, "action"] == db.loc[eid, "action"] and (
            da.loc[eid, "side"] == db.loc[eid, "side"] or (
                pd.isna(da.loc[eid, "side"]) and pd.isna(db.loc[eid, "side"])
            )
        ):
            agree += 1
    st.metric(
        f"결정 일치율 ({a} vs {b})",
        f"{agree}/{len(common)} = {agree/len(common):.1%}",
    )

    # 4-칸 cross-tab
    rows = []
    for eid in common:
        rows.append({
            "event_id": eid,
            f"{a}_side": da.loc[eid, "side"] or "hold",
            f"{b}_side": db.loc[eid, "side"] or "hold",
        })
    paired_df = pd.DataFrame(rows)
    cross = pd.crosstab(paired_df[f"{a}_side"], paired_df[f"{b}_side"])
    st.markdown("#### 결정 cross-tab")
    st.dataframe(cross, use_container_width=True)

    if scored:
        # McNemar (paired hits)
        try:
            from ai_agent.backtest.stats import mcnemar_paired
        except Exception:
            st.warning("ai_agent.backtest.stats import 실패 — McNemar skip")
            return
        a_hits, b_hits = [], []
        for eid in common:
            ar = da.loc[eid, "raw_return"]
            br = db.loc[eid, "raw_return"]
            a_hits.append(bool(pd.notna(ar) and ar > 0))
            b_hits.append(bool(pd.notna(br) and br > 0))
        result = mcnemar_paired(a_hits, b_hits)
        st.markdown("#### McNemar paired test (hit-based)")
        c1, c2, c3 = st.columns(3)
        c1.metric(f"{a}만 hit", result.b)
        c2.metric(f"{b}만 hit", result.c)
        c3.metric("p-value", f"{result.p_value:.4f}")
        st.caption(result.interpretation)


# ============================================================
# Run launcher — backtest subprocess + 로그 tail
# ============================================================


def _repo_root() -> Path:
    """dashboards/backtest_viewer.py → repo root (ai_agent의 부모)."""
    return Path(__file__).resolve().parents[2]


def _runs_root() -> Path:
    return Path(__file__).resolve().parent.parent / "backtest" / "runs"


def _build_run_command(args: dict[str, Any], output_dir: Path) -> list[str]:
    """form 값 → run_ai_backtest CLI 인자 리스트.

    sys.executable로 동일 venv의 python 사용. cwd는 repo root.
    """
    cmd = [
        sys.executable, "-m", "ai_agent.backtest.run_ai_backtest",
        "--mode", args["mode"],
        "--start", args["start"].isoformat(),
        "--end", args["end"].isoformat(),
        "--user-id", str(args["user_id"]),
        "--output", str(output_dir),
        "--initial-cash", str(int(args["initial_cash"])),
        "--holding-days", str(int(args["holding_days"])),
        "--backtest-user-id", str(int(args["backtest_user_id"])),
    ]
    if args.get("watchlist"):
        cmd += ["--watchlist", args["watchlist"]]
    if args.get("initial_holdings"):
        cmd += ["--initial-holdings", args["initial_holdings"]]
    if args.get("score_after"):
        cmd.append("--score-after")
    if args.get("pm_mock"):
        cmd.append("--pm-mock")
    if args.get("reset_memory"):
        cmd.append("--reset-memory")
    return cmd


def _spawn_backtest(cmd: list[str], output_dir: Path) -> dict[str, Any]:
    """run_ai_backtest를 별도 프로세스로 spawn. stdout/stderr → run.log.

    Returns run state dict — st.session_state에 저장.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    log_path = output_dir / "run.log"
    log_fp = log_path.open("w", encoding="utf-8", buffering=1)
    log_fp.write(f"$ {' '.join(cmd)}\n\n")
    log_fp.flush()

    # Windows에서도 동작. shell=False로 인자 안전 전달. cwd=repo root로 ai_agent 패키지 import.
    proc = subprocess.Popen(
        cmd,
        cwd=str(_repo_root()),
        stdout=log_fp,
        stderr=subprocess.STDOUT,
        env={**os.environ, "PYTHONUNBUFFERED": "1"},
    )
    return {
        "pid": proc.pid,
        "log_path": str(log_path),
        "output_dir": str(output_dir),
        "cmd": cmd,
        "started_at": datetime.now().isoformat(timespec="seconds"),
    }


def _proc_is_alive(pid: int) -> bool:
    """psutil 없이 OS 호환 alive 체크. Windows/Unix 모두 동작."""
    if pid <= 0:
        return False
    try:
        if os.name == "nt":
            import ctypes
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            STILL_ACTIVE = 259
            kernel32 = ctypes.windll.kernel32
            h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
            if not h:
                return False
            exit_code = ctypes.c_ulong()
            kernel32.GetExitCodeProcess(h, ctypes.byref(exit_code))
            kernel32.CloseHandle(h)
            return exit_code.value == STILL_ACTIVE
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _tail_log(log_path: str, n_lines: int = 80) -> str:
    p = Path(log_path)
    if not p.exists():
        return "(아직 로그 없음)"
    try:
        with p.open("r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        return "".join(lines[-n_lines:]) or "(빈 로그)"
    except Exception as e:
        return f"(로그 읽기 실패: {e})"


def tab_run() -> None:
    """backtest를 subprocess로 띄우고 진행 로그를 실시간 표시."""
    st.subheader("🚀 백테스트 실행")

    running = st.session_state.get("running_proc")

    if running and _proc_is_alive(running["pid"]):
        st.info(f"진행 중 — PID {running['pid']} · 시작 {running['started_at']}")
        st.code(" ".join(running["cmd"]), language="bash")

        col1, col2 = st.columns([3, 1])
        with col1:
            st.markdown("**실시간 로그 (마지막 80줄)**")
        with col2:
            if st.button("⛔ 강제 종료", help="subprocess kill"):
                try:
                    if os.name == "nt":
                        subprocess.call(["taskkill", "/F", "/PID", str(running["pid"])])
                    else:
                        os.kill(running["pid"], 9)
                    st.warning("종료 신호 전송")
                except Exception as e:
                    st.error(f"종료 실패: {e}")
                time.sleep(1)
                st.rerun()

        st.code(_tail_log(running["log_path"]), language="text")

        # 진행 중에만 자동 새로고침 (2초). 새로고침 시 polling 계속.
        time.sleep(2)
        st.rerun()
        return

    if running and not _proc_is_alive(running["pid"]):
        # 직전 run 완료 — 결과 표시 + 사이드바 자동 선택 안내
        st.success(f"✅ 완료 — 결과: `{running['output_dir']}`")
        st.caption(f"PID {running['pid']} · 시작 {running['started_at']}")
        with st.expander("실행 로그 (전체)", expanded=False):
            st.code(_tail_log(running["log_path"], n_lines=500), language="text")
        col1, col2 = st.columns(2)
        if col1.button("새 실행 준비"):
            st.session_state.pop("running_proc", None)
            st.rerun()
        col2.caption("👈 사이드바 'Backtest run 선택'에서 결과 디렉터리를 골라 분석 탭으로 이동하세요.")
        return

    # form
    st.caption("옵션을 선택하고 '백테스트 시작'을 누르면 별도 프로세스로 실행됩니다. "
               "이 탭을 떠나거나 페이지를 새로고침해도 백그라운드에서 계속 돌아갑니다.")

    with st.form("backtest_form"):
        c1, c2, c3 = st.columns(3)
        with c1:
            mode = st.selectbox(
                "모드", ["A", "B", "random", "mock"],
                help="A=Bull/Bear 토론 (MVP, LLM 4회/결정), B=단일 에이전트 ablation, "
                     "random/mock=LLM 없는 baseline",
            )
            start = st.date_input("시작일", value=date(2024, 1, 2))
            end = st.date_input("종료일", value=date(2024, 1, 15))
        with c2:
            watchlist = st.text_input(
                "종목 (쉼표 구분)", value="005930,000660",
                help="비우면 활성 종목 자동",
            )
            initial_cash = st.number_input(
                "초기 현금 (KRW)", min_value=0, value=10_000_000, step=1_000_000,
            )
            initial_holdings = st.text_input(
                "초기 보유 (CODE:QTY,...)", value="005930:100,000660:50",
                help="SELL 결정이 정상 체결되려면 필요. 비워두면 매수만 가능.",
            )
        with c3:
            holding_days = st.number_input("최대 보유일 (T+N)", min_value=1, max_value=60, value=7)
            backtest_user_id = st.number_input(
                "backtest_user_id", min_value=1, value=99999,
                help="DB 격리용. 운영 user_id(보통 4자리 이하)와 분리하세요.",
            )
            user_id = st.text_input("DA user_id (string)", value="backtest-user")

        c4, c5, c6, c7 = st.columns(4)
        with c4:
            score_after = st.checkbox("score-after", value=True,
                                       help="scored_*.jsonl 생성 (PnL/회고/Calibration 탭 활성)")
        with c5:
            pm_mock = st.checkbox("pm-mock", value=False,
                                   help="회고를 fake로 생성 (LLM 비용 절감, 진짜 reflection loop 안 닫힘)")
        with c6:
            reset_memory = st.checkbox("reset-memory", value=True,
                                        help="run 시작 전 backtest_user_id의 ai_judgments / "
                                             "post_mortem_reports DELETE. 회차 격리.")
        with c7:
            run_name = st.text_input(
                "결과 폴더명",
                value=f"ui_{datetime.now().strftime('%Y%m%d_%H%M%S')}",
                help="ai_agent/backtest/runs/<이름>/ 에 저장",
            )

        submitted = st.form_submit_button("🚀 백테스트 시작", type="primary",
                                          use_container_width=True)

    if not submitted:
        st.caption("⏱ mode A는 결정당 LLM 4회 호출 — 2주 × 2종목 = 약 5~15분 소요 예상.")
        return

    # 입력 검증
    if start >= end:
        st.error("시작일은 종료일보다 이전이어야 합니다.")
        return
    if mode in ("A", "B") and not (os.getenv("GMS_KEY") or os.getenv("ANTHROPIC_API_KEY")
                                    or os.getenv("XAI_API_KEY")):
        st.warning("⚠️ mode A/B는 LLM 키 필요 — .env에 GMS_KEY 등이 있는지 확인하세요. 그래도 진행합니다.")

    output_dir = _runs_root() / run_name
    args = {
        "mode": mode, "start": start, "end": end,
        "user_id": user_id, "watchlist": watchlist, "initial_cash": initial_cash,
        "initial_holdings": initial_holdings, "holding_days": holding_days,
        "backtest_user_id": backtest_user_id,
        "score_after": score_after, "pm_mock": pm_mock, "reset_memory": reset_memory,
    }
    cmd = _build_run_command(args, output_dir)
    try:
        state = _spawn_backtest(cmd, output_dir)
    except Exception as e:
        st.error(f"spawn 실패: {e}")
        return

    st.session_state["running_proc"] = state
    st.success(f"🚀 시작 — PID {state['pid']}")
    time.sleep(1)
    st.rerun()


# ============================================================
# Main
# ============================================================


def main() -> None:
    st.set_page_config(
        page_title="MODU Backtest Viewer",
        layout="wide",
        initial_sidebar_state="expanded",
    )
    # 약간의 CSS 폴리시
    st.markdown("""
        <style>
        .stMetric { background: rgba(128,128,128,0.06); padding: 12px; border-radius: 8px; }
        [data-testid="stMetricValue"] { font-size: 1.6rem; }
        [data-testid="stMetricLabel"] { font-size: 0.85rem; opacity: 0.7; }
        h3 { padding-top: 8px; margin-bottom: 8px; }
        h4 { padding-top: 12px; font-size: 1.05rem; opacity: 0.85; }
        </style>
    """, unsafe_allow_html=True)
    st.title("MODU Backtesting")
    st.caption("Bull/Bear 토론 + LangGraph 의사결정 평가")

    st.sidebar.markdown("### 데이터 입력")

    # 여러 후보 위치 자동 검색 — JSONL 들어있는 디렉터리들
    # 우선순위: ai_agent/backtest/runs (메인) → dummy → 레거시 위치
    search_roots = [
        Path("backtest/runs"),                # cwd=ai_agent
        Path("ai_agent/backtest/runs"),       # cwd=repo root
        Path("backtest/dummy"),
        Path("ai_agent/backtest/dummy"),
        # 레거시 (이전 산출물이 남아있으면)
        Path("runs"),
        Path("ai_agent/runs"),
        Path("backtest_out"),
        Path("../backtest_out"),
    ]

    # 1년치 통합 로드를 위해 디렉터리 단위로 모음
    run_dirs: list[Path] = []
    for root in search_roots:
        if not root.exists():
            continue
        # 디렉터리 자체에 jsonl이 있으면 그 디렉터리
        if any(root.glob("*.jsonl")):
            run_dirs.append(root)
        # 하위 디렉터리에 jsonl이 있는 경우
        for sub in root.iterdir():
            if sub.is_dir() and any(sub.glob("*.jsonl")):
                run_dirs.append(sub)
    run_dirs = sorted(set(run_dirs))

    if not run_dirs:
        st.sidebar.warning("JSONL 데이터를 찾을 수 없습니다.")
        path_str = st.sidebar.text_input("디렉터리 경로", value="backtest_out/mode_A_2024")
        run_dir = Path(path_str)
    else:
        labels = [_run_dir_label(p) for p in run_dirs]
        idx = st.sidebar.selectbox(
            "Backtest run 선택",
            options=range(len(run_dirs)),
            format_func=lambda i: labels[i],
        )
        run_dir = run_dirs[idx]

    df = load_run_dir(str(run_dir))
    if df.empty:
        st.warning(f"디렉터리가 비어있거나 읽을 수 없음: {run_dir}. "
                   f"'🚀 실행' 탭에서 새 백테스트를 돌리세요.")
        tab_run()
        st.stop()

    scored = detect_scored(df)

    # 통합 로드 정보
    n_files = len(list(run_dir.glob("*.jsonl")))
    n_scored = len(list(run_dir.glob("scored_*.jsonl")))
    st.sidebar.caption(
        f"📁 {run_dir.name}\n\n"
        f"파일 {n_files}개 (scored {n_scored}개) · 결정 {len(df):,}건"
    )
    if scored:
        st.sidebar.success("✅ scored 데이터 — 모든 탭 활성")
    else:
        st.sidebar.info("ℹ️ triggers only — Quality/PnL/Calibration 비활성")

    df_filtered = sidebar_filters(df)

    tab_r, tab1, tab2, tab3, tab4, tab5, tab6, tab7 = st.tabs(
        ["🚀 실행", "Overview", "Quality", "PnL", "Calibration", "Reasoning", "거래 차트", "Comparison"]
    )
    equity_df = _load_equity_curve(str(run_dir))

    with tab_r:
        tab_run()
    with tab1:
        tab_overview(df_filtered)
    with tab2:
        tab_quality(df_filtered, scored)
    with tab3:
        tab_pnl(df_filtered, scored, equity_df)
    with tab4:
        tab_calibration(df_filtered, scored)
    with tab5:
        tab_reasoning(df_filtered)
    with tab6:
        tab_trade_chart(df_filtered)
    with tab7:
        tab_comparison(df_filtered, scored)


if __name__ == "__main__":
    main()
