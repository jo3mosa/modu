from typing import Any

from app.agents.memory.kb_loader import KnowledgeBaseLoader
from app.agents.memory.trade_log_repository import TradeLogRepository
from app.state.investment_state import InvestmentAgentState


def memory_agent(state: InvestmentAgentState) -> dict[str, Any]:
    """
    Memory Agent는 투자 의사결정에 필요한 다양한 정보를 수집하여
    종합적인 메모리 컨텍스트를 구축하는 역할을 합니다.

    - investment-profile.md 로드
    - investment-strategy.md 로드
    - trade-history-wiki.md 로드
    - llm-wiki.md 로드
    - trade_logs DB에서 현재 후보 종목/섹터/key_signal 기준 최근 거래 조회
    """

    user_id = "user_001"
    candidate_assets = state.candidate_assets
    analysis_snapshot = state.analysis_snapshot

    tickers = _extract_tickers(candidate_assets)
    sectors = _extract_sectors(candidate_assets)
    key_signals = _extract_key_signals(analysis_snapshot)

    kb_loader = KnowledgeBaseLoader()

    investment_profile = kb_loader.load_yaml_sections("investment-profile.md")
    investment_strategy = kb_loader.load_yaml_sections("investment-strategy.md")
    trade_history_wiki = kb_loader.load_markdown("trade-history-wiki.md")
    llm_wiki = kb_loader.load_markdown("llm-wiki.md")

    user_context = _build_user_context(investment_profile)
    policy_context = _build_policy_context(investment_strategy, user_context)

    if not tickers and not sectors and not key_signals:
        return {
            "memory_context": {
                "query_basis": {
                    "tickers": [],
                    "sectors": [],
                    "key_signals": [],
                },
                "recent_similar_trades": [],
                "recent_loss_trades": [],
                "summary": "조회 기준이 없어 최근 거래 이력 조회를 생략했습니다.",
            },
            "user_context": user_context,
            "policy_context": policy_context,
            "history_context": _build_history_context(
                trade_history_wiki=trade_history_wiki,
                llm_wiki=llm_wiki,
                key_signals=[],
            ),
        }

    trade_log_repository = TradeLogRepository()

    recent_trades = trade_log_repository.find_recent_similar_trades(
        user_id=user_id,
        tickers=tickers,
        sectors=sectors,
        key_signals=key_signals,
        limit=10,
    )

    recent_loss_trades = trade_log_repository.find_recent_loss_trades(
        user_id=user_id,
        tickers=tickers,
        sectors=sectors,
        key_signals=key_signals,
        limit=5,
    )

    return {
        "memory_context": _build_memory_context(
            tickers=tickers,
            sectors=sectors,
            key_signals=key_signals,
            recent_trades=recent_trades,
            recent_loss_trades=recent_loss_trades,
        ),
        "user_context": user_context,
        "policy_context": policy_context,
        "history_context": _build_history_context(
            trade_history_wiki=trade_history_wiki,
            llm_wiki=llm_wiki,
            key_signals=key_signals,
        ),
    }


def _extract_tickers(candidate_assets: list[dict[str, Any]]) -> list[str]:
    return list({
        asset.get("stock_code") or asset.get("ticker")
        for asset in candidate_assets
        if asset.get("stock_code") or asset.get("ticker")
    })


def _extract_sectors(candidate_assets: list[dict[str, Any]]) -> list[str]:
    return list({
        asset["sector"]
        for asset in candidate_assets
        if asset.get("sector")
    })


def _extract_key_signals(analysis_snapshot: dict[str, Any]) -> list[str]:
    signals = analysis_snapshot.get("key_signals", [])
    return signals if isinstance(signals, list) else []


def _build_user_context(investment_profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "investor_type": investment_profile.get("investor_type", {}),
        "risk_rules": investment_profile.get("risk_rules", {}),
        "domestic_stock_risk_policy": investment_profile.get(
            "domestic_stock_risk_policy",
            {},
        ),
    }


def _build_policy_context(
    investment_strategy: dict[str, Any],
    user_context: dict[str, Any],
) -> dict[str, Any]:
    investor_code = (
        user_context
        .get("investor_type", {})
        .get("code")
    )

    system_constraints = investment_strategy.get("system_trading_constraints", {})
    investor_constraints = system_constraints.get("investor_type_constraints", {})

    max_single_stock_ratio = (
        investor_constraints
        .get(investor_code, {})
        .get("max_single_stock_ratio")
    )

    return {
        "decision_priority": investment_strategy.get("decision_priority", []),
        "asset_allocation": investment_strategy.get("asset_allocation", {}),
        "system_trading_constraints": {
            **system_constraints,
            "resolved_max_single_stock_ratio": max_single_stock_ratio,
        },
        "risk_management": investment_strategy.get("risk_management", {}),
        "risk_rule_priority": investment_strategy.get("risk_rule_priority", []),
        "market_rules": investment_strategy.get("market_rules", {}),
        "entry_rules": investment_strategy.get("entry_rules", {}),
        "position_management": investment_strategy.get("position_management", {}),
        "hard_constraints": investment_strategy.get("hard_constraints", []),
        "explanation_policy": investment_strategy.get("explanation_policy", {}),
    }


def _build_memory_context(
    tickers: list[str],
    sectors: list[str],
    key_signals: list[str],
    recent_trades: list[dict[str, Any]],
    recent_loss_trades: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "query_basis": {
            "tickers": tickers,
            "sectors": sectors,
            "key_signals": key_signals,
        },
        "recent_similar_trades": recent_trades,
        "recent_loss_trades": recent_loss_trades,
        "summary": _summarize_trade_memory(recent_trades, recent_loss_trades),
    }


def _build_history_context(
    trade_history_wiki: str,
    llm_wiki: str,
    key_signals: list[str],
) -> dict[str, Any]:
    return {
        "trade_history_wiki": trade_history_wiki,
        "relevant_indicator_guides": _filter_llm_wiki(
            llm_wiki=llm_wiki,
            key_signals=key_signals,
        ),
    }


def _summarize_trade_memory(
    recent_trades: list[dict[str, Any]],
    recent_loss_trades: list[dict[str, Any]],
) -> str:
    if not recent_trades and not recent_loss_trades:
        return "현재 후보와 직접적으로 유사한 최근 거래 이력은 확인되지 않았습니다."

    return (
        f"유사 거래 {len(recent_trades)}건, "
        f"최근 손실 거래 {len(recent_loss_trades)}건이 확인되었습니다. "
        "Supervisor Agent는 최근 손실 거래의 판단 근거와 현재 신호의 중복 여부를 우선 확인해야 합니다."
    )


def _filter_llm_wiki(llm_wiki: str, key_signals: list[str]) -> dict[str, str]:
    signal_to_keywords = {
        "golden_cross": ["Golden Cross", "골든크로스"],
        "rsi_oversold": ["RSI"],
        "rsi_rebound": ["RSI"],
        "ma_breakout": ["Moving Average", "이동평균"],
    }

    result: dict[str, str] = {}

    for signal in key_signals:
        keywords = signal_to_keywords.get(signal, [])

        for keyword in keywords:
            section = _extract_markdown_section(llm_wiki, keyword)

            if section:
                result[signal] = section
                break

    return result


def _extract_markdown_section(markdown: str, heading_keyword: str) -> str | None:
    lines = markdown.splitlines()
    start_idx = None

    for idx, line in enumerate(lines):
        if line.startswith("## ") and heading_keyword.lower() in line.lower():
            start_idx = idx
            break

    if start_idx is None:
        return None

    end_idx = len(lines)

    for idx in range(start_idx + 1, len(lines)):
        if lines[idx].startswith("## "):
            end_idx = idx
            break

    return "\n".join(lines[start_idx:end_idx]).strip()