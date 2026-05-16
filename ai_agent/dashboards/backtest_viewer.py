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
from collections import Counter
from pathlib import Path
from typing import Any

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import streamlit as st

# ============================================================
# Data loading
# ============================================================


@st.cache_data(show_spinner=False)
def load_jsonl(path: str) -> pd.DataFrame:
    """JSONL을 DataFrame으로. 두 포맷 자동 정규화:
      1. app.backtest (우리 모듈): date / action / side / event_id / bull_claim / ...
      2. ai_agent.backtest (DA framework + adapter): as_of_date / decision.action / fill / ...
    """
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
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    if "as_of_date" in df.columns and "date" not in df.columns:
        df = _normalize_da_format(df)
    if "date" in df.columns:
        df["date"] = pd.to_datetime(df["date"], errors="coerce")
    return df


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
    out["mode"] = out.get("mode", "DA")  # DA 포맷은 mode 컬럼 없을 수 있음

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

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("총 결정", n_total)
    c2.metric("거래", n_trade)
    c3.metric("BUY / SELL", f"{n_buy} / {n_sell}")
    c4.metric("HOLD", n_hold)

    st.markdown("#### 결정 분포")
    dist_df = pd.DataFrame({
        "category": ["BUY", "SELL", "HOLD", "other"],
        "count": [n_buy, n_sell, n_hold, n_total - n_buy - n_sell - n_hold],
    })
    dist_df = dist_df[dist_df["count"] > 0]
    fig = px.pie(dist_df, names="category", values="count", hole=0.4)
    st.plotly_chart(fig, use_container_width=True)

    if "date" in df.columns:
        st.markdown("#### 시간별 결정 수")
        timeline = df.groupby([df["date"].dt.date, "mode"]).size().reset_index(name="count")
        timeline.columns = ["date", "mode", "count"]
        fig = px.bar(timeline, x="date", y="count", color="mode", barmode="group")
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
        st.info("scored JSONL이 없어 hit_rate 계산 불가. 사이드바에서 scored 파일 경로를 추가하세요.")
        return
    if df.empty:
        st.warning("데이터 없음.")
        return

    st.markdown("#### 모드별 hit rate")
    by_mode = (
        df.groupby("mode", dropna=False)
        .apply(_compute_hit_rate)
        .apply(pd.Series)
        .reset_index()
    )
    by_mode["hit_rate"] = by_mode["hit_rate"].fillna(0.0)
    fig = px.bar(by_mode, x="mode", y="hit_rate", text="hit_rate", color="mode")
    fig.update_traces(texttemplate="%{text:.2%}", textposition="outside")
    fig.update_yaxes(range=[0, 1])
    st.plotly_chart(fig, use_container_width=True)
    st.dataframe(by_mode, use_container_width=True)

    st.markdown("#### 분기별 hit rate (memory 누적 효과 확인용)")
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
        fig = px.line(by_q, x="quarter", y="hit_rate", color="mode", markers=True)
        fig.update_yaxes(range=[0, 1])
        st.plotly_chart(fig, use_container_width=True)


# ============================================================
# Tab: PnL
# ============================================================


def tab_pnl(df: pd.DataFrame, scored: bool) -> None:
    st.markdown("### 투자 결과 (PnL)")
    if not scored:
        st.info("scored JSONL이 없어 PnL 분석 불가.")
        return
    trades = df[df["raw_return"].notna()].copy() if "raw_return" in df.columns else pd.DataFrame()
    if trades.empty:
        st.warning("거래 결정이 없음.")
        return

    by_mode_summary = (
        trades.groupby("mode")
        .agg(
            n=("raw_return", "count"),
            win_rate=("raw_return", lambda s: (s > 0).mean()),
            avg_return=("raw_return", "mean"),
            cum_return=("raw_return", lambda s: (1 + s).prod() - 1),
        )
        .reset_index()
    )
    st.markdown("#### 모드별 요약")
    st.dataframe(by_mode_summary, use_container_width=True)

    st.markdown("#### 거래별 수익률 분포")
    fig = px.histogram(trades, x="raw_return", color="mode", nbins=40, barmode="overlay", opacity=0.6)
    fig.add_vline(x=0, line_dash="dash")
    st.plotly_chart(fig, use_container_width=True)

    st.markdown("#### 누적 수익률 곡선 (단순 합산, 비복리)")
    trades_sorted = trades.sort_values("date").copy()
    trades_sorted["cum"] = trades_sorted.groupby("mode")["raw_return"].cumsum()
    fig = px.line(trades_sorted, x="date", y="cum", color="mode")
    fig.add_hline(y=0, line_dash="dash")
    st.plotly_chart(fig, use_container_width=True)


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
    st.markdown("#### ECE (Expected Calibration Error)")
    st.dataframe(ece_df, use_container_width=True)

    st.markdown("#### Reliability Diagram")
    fig = go.Figure()
    fig.add_trace(go.Scatter(
        x=[0, 1], y=[0, 1], mode="lines", name="perfect",
        line=dict(dash="dash", color="gray"),
    ))
    for mode, sub in bin_df.groupby("mode"):
        fig.add_trace(go.Scatter(
            x=sub["avg_confidence"], y=sub["hit_rate"],
            mode="markers+lines", name=str(mode),
            text=sub["n"].apply(lambda x: f"n={x}"),
        ))
    fig.update_xaxes(range=[0, 1], title="평균 confidence")
    fig.update_yaxes(range=[0, 1], title="실제 hit rate")
    st.plotly_chart(fig, use_container_width=True)

    st.markdown("#### bin별 표본 분포")
    fig = px.bar(bin_df, x="bin", y="n", color="mode", barmode="group")
    st.plotly_chart(fig, use_container_width=True)


# ============================================================
# Tab: Reasoning Samples
# ============================================================


def tab_reasoning(df: pd.DataFrame) -> None:
    st.markdown("### 추론 텍스트 뷰어")
    if df.empty:
        st.warning("데이터 없음.")
        return

    # 식별자 후보
    options = []
    for _, row in df.iterrows():
        label = f"{row.get('date', '')} | {row.get('mode', '')} | {row.get('stock_code', '')} | {row.get('action', '')}/{row.get('side', '')}"
        options.append((label, row.get("event_id")))

    selected_label = st.selectbox(
        "결정 선택",
        options=[label for label, _ in options],
        index=0 if options else None,
    )
    if not selected_label:
        return
    matching = [eid for label, eid in options if label == selected_label]
    if not matching:
        return
    row = df[df["event_id"] == matching[0]].iloc[0]

    c1, c2, c3 = st.columns(3)
    c1.metric("Decision", f"{row.get('action')} / {row.get('side')}")
    c2.metric("Confidence", f"{row.get('confidence', 0):.2f}" if pd.notna(row.get("confidence")) else "-")
    c3.metric("Winning side", str(row.get("winning_side") or "-"))

    if pd.notna(row.get("judgment_reason")):
        st.markdown("**판단 사유**")
        st.write(row["judgment_reason"])
    if pd.notna(row.get("bull_claim")):
        st.markdown("**Bull 주장**")
        st.write(row["bull_claim"])
    if pd.notna(row.get("bear_claim")):
        st.markdown("**Bear 주장**")
        st.write(row["bear_claim"])

    pm = row.get("post_mortem")
    if isinstance(pm, dict):
        st.markdown("---")
        st.markdown("### 사후 회고 (Post Mortem)")
        st.markdown(f"**요약**: {pm.get('summary', '-')}")
        for key, ko in [
            ("entry_timing_assessment", "진입 시점 평가"),
            ("exit_rule_assessment", "익절/손절 평가"),
            ("risk_prediction_accuracy", "리스크 예측 정확도"),
        ]:
            if pm.get(key):
                st.markdown(f"**{ko}**: {pm[key]}")
        if pm.get("missed_signals"):
            st.markdown("**놓친 신호**")
            for s in pm["missed_signals"]:
                st.markdown(f"- {s}")
        if pm.get("lessons"):
            st.markdown("**다음 결정에 적용할 교훈**")
            for s in pm["lessons"]:
                st.markdown(f"- {s}")


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
# Main
# ============================================================


def main() -> None:
    st.set_page_config(page_title="MODU Backtest Viewer", layout="wide")
    st.title("MODU Backtest 결과 뷰어")

    st.sidebar.markdown("### 데이터 입력")
    default_dir = Path("runs")
    candidates = sorted(default_dir.glob("**/*.jsonl")) if default_dir.exists() else []
    if candidates:
        path_str = st.sidebar.selectbox(
            "JSONL 선택 (runs/ 하위)",
            options=[str(p) for p in candidates],
        )
    else:
        path_str = st.sidebar.text_input("JSONL 경로 입력", value="runs/decisions.jsonl")

    df = load_jsonl(path_str)
    if df.empty:
        st.warning(f"파일을 읽을 수 없거나 비어있음: {path_str}")
        st.stop()

    scored = detect_scored(df)
    st.sidebar.caption(f"파일 형식: {'scored' if scored else 'decisions only'}  ·  행 수: {len(df)}")

    df_filtered = sidebar_filters(df)

    tab1, tab2, tab3, tab4, tab5, tab6 = st.tabs(
        ["Overview", "Quality", "PnL", "Calibration", "Reasoning", "Comparison"]
    )
    with tab1:
        tab_overview(df_filtered)
    with tab2:
        tab_quality(df_filtered, scored)
    with tab3:
        tab_pnl(df_filtered, scored)
    with tab4:
        tab_calibration(df_filtered, scored)
    with tab5:
        tab_reasoning(df_filtered)
    with tab6:
        tab_comparison(df_filtered, scored)


if __name__ == "__main__":
    main()
