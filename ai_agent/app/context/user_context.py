from typing import Any

from app.agents.memory.kb_loader import KnowledgeBaseLoader


def load_user_context(
    user_id: int,
    kb_loader: KnowledgeBaseLoader,
) -> dict[str, Any]:
    """
    사용자 투자 성향 및 리스크 설정을 로드한다.

    MVP: KB 파일(investment-profile.md) 기반
    이후: user_id 기준 DB 조회로 교체
    """
    profile = kb_loader.load_yaml_sections("investment-profile.md")

    return {
        "investor_type": profile.get("investor_type", {}),
        "risk_rules": profile.get("risk_rules", {}),
        "domestic_stock_risk_policy": profile.get("domestic_stock_risk_policy", {}),
    }


def load_policy_context(
    user_id: int,
    kb_loader: KnowledgeBaseLoader,
    user_context: dict[str, Any],
) -> dict[str, Any]:
    """
    서비스 공통 투자 정책을 로드하고, 사용자 투자 성향에 따른 종목 비중 제한을 산출한다.

    MVP: KB 파일(investment-strategy.md) 기반
    이후: 정책 테이블 DB 조회로 교체
    """
    strategy = kb_loader.load_yaml_sections("investment-strategy.md")

    investor_code = user_context.get("investor_type", {}).get("code")
    system_constraints = strategy.get("system_trading_constraints", {})
    max_single_stock_ratio = (
        system_constraints
        .get("investor_type_constraints", {})
        .get(investor_code, {})
        .get("max_single_stock_ratio")
    )

    return {
        "decision_priority": strategy.get("decision_priority", []),
        "asset_allocation": strategy.get("asset_allocation", {}),
        "system_trading_constraints": {
            **system_constraints,
            "resolved_max_single_stock_ratio": max_single_stock_ratio,
        },
        "risk_management": strategy.get("risk_management", {}),
        "risk_rule_priority": strategy.get("risk_rule_priority", []),
        "market_rules": strategy.get("market_rules", {}),
        "entry_rules": strategy.get("entry_rules", {}),
        "position_management": strategy.get("position_management", {}),
        "hard_constraints": strategy.get("hard_constraints", []),
        "explanation_policy": strategy.get("explanation_policy", {}),
    }


def load_history_context(
    user_id: int,
    kb_loader: KnowledgeBaseLoader,
    key_signals: list[str],
) -> dict[str, Any]:
    """
    과거 거래 복기 문서와 금융 지표 설명을 로드한다.

    MVP: KB 파일(trade-history-wiki.md, llm-wiki.md) 기반
    이후: trade_history_wiki 테이블 DB 조회로 교체
    """
    trade_history_wiki = kb_loader.load_markdown("trade-history-wiki.md")
    llm_wiki = kb_loader.load_markdown("llm-wiki.md")

    return {
        "trade_history_wiki": trade_history_wiki,
        "relevant_indicator_guides": _filter_llm_wiki(llm_wiki, key_signals),
    }


def _filter_llm_wiki(llm_wiki: str, key_signals: list[str]) -> dict[str, str]:
    signal_to_keywords: dict[str, list[str]] = {
        "technical_signal": ["RSI", "Moving Average", "이동평균"],
        "fundamental_signal": ["PER", "PBR", "Valuation", "저평가"],
        "event_signal": [],
        "sentiment_signal": [],
    }

    result: dict[str, str] = {}

    for signal in key_signals:
        sections: list[str] = []
        for keyword in signal_to_keywords.get(signal, []):
            section = _extract_markdown_section(llm_wiki, keyword)
            if section:
                sections.append(section)
        if sections:
            result[signal] = "\n\n".join(sections)

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
