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

    # TODO:
    # 현재는 user_id가 state에 없어서 임시 하드코딩.
    # 추후 InvestmentAgentState에 user_id를 추가하거나 인증 사용자 정보에서 주입해야 함.
    user_id = "user_001"
    candidate_assets = state.candidate_assets
    analysis_snapshot = state.analysis_snapshot

    # DB 조회에 사용할 기준값 추출
    tickers = _extract_tickers(candidate_assets)
    sectors = _extract_sectors(candidate_assets)
    key_signals = _extract_key_signals(analysis_snapshot)

    # Knowledge Base 파일 로더 생성
    kb_loader = KnowledgeBaseLoader()

    investment_profile = kb_loader.load_yaml_sections("investment-profile.md")
    investment_strategy = kb_loader.load_yaml_sections("investment-strategy.md")
    # 현재는 전체 문서를 넣고 있음.
    # 추후에는 현재 key_signal과 관련 있는 섹션만 필터링하는 방식으로 개선 예정
    trade_history_wiki = kb_loader.load_markdown("trade-history-wiki.md")
    llm_wiki = kb_loader.load_markdown("llm-wiki.md")

    user_context = _build_user_context(investment_profile)
    policy_context = _build_policy_context(investment_strategy, user_context)

    # 조회 기준이 하나도 없으면 DB를 호출하지XX
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

    # trade_logs DB 접근 객체 생성
    trade_log_repository = TradeLogRepository()

    # 동일 종목/섹터/key_signal 기준 최근 유사 거래 조회
    recent_trades = trade_log_repository.find_recent_similar_trades(
        user_id=user_id,
        tickers=tickers,
        sectors=sectors,
        key_signals=key_signals,
        limit=10,
    )

    # 유사 조건 중 손실이 발생한 최근 거래만 별도로 조회
    # Supervisor Agent가 보수적으로 판단해야 할 근거로 활용 
    recent_loss_trades = trade_log_repository.find_recent_loss_trades(
        user_id=user_id,
        tickers=tickers,
        sectors=sectors,
        key_signals=key_signals,
        limit=5,
    )

    # Memory Agent가 state에 쓸 값 반환
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
    """
    후보 종목 목록에서 종목 코드를 추출한다.

    현재 프로젝트에서는 Kafka/Analysis 포맷이 stock_code를 사용하고,
    일부 코드에서는 ticker를 사용할 수 있으므로 둘 다 대응한다.

    예:
    [{"stock_code": "005930"}] -> ["005930"]
    [{"ticker": "005930"}] -> ["005930"]
    """

    return list({
        asset.get("stock_code") or asset.get("ticker")
        for asset in candidate_assets
        if asset.get("stock_code") or asset.get("ticker")
    })


def _extract_sectors(candidate_assets: list[dict[str, Any]]) -> list[str]:
    """
    후보 종목 목록에서 sector 값을 추출한다.

    sector가 없는 종목은 제외한다.
    추후 sector 정보가 candidate_assets에 없다면,
    별도 종목 메타 테이블에서 stock_code 기준으로 조회하는 구조가 필요하다.
    """

    return list({
        asset["sector"]
        for asset in candidate_assets
        if asset.get("sector")
    })


def _extract_key_signals(analysis_snapshot: dict[str, Any]) -> list[str]:
    """
    Analysis Layer 결과에서 Memory DB 조회용 key_signal을 추출한다.

    주의:
    - 여기서 RSI 82.5, PER 12.5 같은 값을 직접 투자 판단하지 않는다.
    - Memory Agent는 Analysis Layer 역할을 다시 수행하면 안 된다.
    - 따라서 세부 지표를 과하게 분해하지 않고,
      technical/fundamental/event/sentiment 수준의 조회 태그만 만든다.

    예:
    analysis_snapshot["signals"]에 technical이 있으면 "technical_signal"
    event.has_urgent_issue가 true이면 "event_signal"
    sentiment.daily_score가 있으면 "sentiment_signal"
    """

    signals = analysis_snapshot.get("signals", {})

    key_signals: set[str] = set()

    if signals.get("technical"):
        key_signals.add("technical_signal")

    if signals.get("fundamental"):
        key_signals.add("fundamental_signal")

    event = signals.get("event", {})
    if event.get("has_urgent_issue"):
        key_signals.add("event_signal")

    sentiment = signals.get("sentiment", {})
    if sentiment.get("daily_score") is not None:
        key_signals.add("sentiment_signal")

    return sorted(key_signals)


def _build_user_context(investment_profile: dict[str, Any]) -> dict[str, Any]:
    """
    investment-profile.md에서 필요한 사용자 관련 필드만 추출한다.

    포함 내용:
    - investor_type: 투자 성향
    - risk_rules: 손절/익절 기준
    - domestic_stock_risk_policy: 종목 위험 등급별 자동매매 허용 정책
    """

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
    """
    investment-strategy.md에서 서비스 공통 정책을 추출하고,
    사용자 투자 성향에 따른 최대 단일 종목 비중을 계산한다.
    """

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
    """
    trade_logs DB 조회 결과를 Memory Context로 구성한다.

    이 값은 이후 Strategy Agent / Supervisor Agent의 LLM 입력에 들어갈 수 있다.
    """

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
    """
    과거 복기 문서와 금융 지표 설명 문서를 history_context로 구성한다.

    현재:
    - trade_history_wiki는 전체 원문을 넣음
    - llm_wiki는 key_signal과 관련 있는 섹션만 일부 추출

    To Do:
    - trade_history_wiki도 key_signal 기반으로 관련 섹션만 추출
    - 너무 긴 문서는 요약하거나 토큰 제한 적용
    """

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
    """
    조회된 거래 이력 개수를 바탕으로 짧은 요약 문장을 만든다.

    현재는 단순 개수 기반 요약.
    추후에는 손실 원인, 반복 패턴, 동일 섹터 손실률 등을 포함할 수 있다.
    """

    if not recent_trades and not recent_loss_trades:
        return "현재 후보와 직접적으로 유사한 최근 거래 이력은 확인되지 않았습니다."

    return (
        f"유사 거래 {len(recent_trades)}건, "
        f"최근 손실 거래 {len(recent_loss_trades)}건이 확인되었습니다. "
        "Supervisor Agent는 최근 손실 거래의 판단 근거와 현재 신호의 중복 여부를 우선 확인해야 합니다."
    )


def _filter_llm_wiki(llm_wiki: str, key_signals: list[str]) -> dict[str, str]:
    """
    key_signal에 따라 llm-wiki.md에서 관련 지표 설명 섹션만 추출한다.
    event_signal, sentiment_signal은 현재 llm-wiki에 대응 섹션이 없다고 보고 비워둔다.
    """

    signal_to_keywords = {
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
    """
    Markdown 문서에서 특정 heading keyword가 포함된 ## 섹션을 추출한다.

    예:
    heading_keyword = "RSI"
    문서에 "## RSI"가 있으면 해당 섹션 전체를 반환한다.
    """

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