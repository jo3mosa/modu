from app.graph.builder import build_investment_graph
from app.state.investment_state import InvestmentAgentState


def main() -> None:
    """
    LangGraph skeleton 실행 진입점

    역할:
    - 초기 InvestmentAgentState 생성
    - 그래프 빌드
    - graph.invoke() 실행
    - 최종 결과 출력
    """
    graph = build_investment_graph()

    initial_state = InvestmentAgentState(
        market_snapshot={
            "index": "KOSPI",
            "market_status": "open",
            "volatility": "normal",
        },
        analysis_snapshot={
            "stock_code": "005930",
            "timestamp": "2026-05-02T08:00:00Z",
            "signals": {
                "technical": {"golden_cross": True, "rsi": 48},
                "fundamental": {},
                "event": {},
                "sentiment": {"score": 0.67},
            },
        },
        candidate_assets=[
            {
                "symbol": "005930",
                "name": "Samsung Electronics",
                "sector": "semiconductor",
            }
        ],
        portfolio_snapshot={
            "holdings": [],
            "cash_balance": 5_000_000,
            "total_assets": 5_000_000,
        },
    )

    # LangGraph 실행
    result = graph.invoke(initial_state)

    print("=== FINAL DECISION ===")
    print(result["final_decision"])

    print("\n=== RISK CHECK RESULT ===")
    print(result["risk_check_result"])

    print("\n=== EXECUTION RESULT ===")
    print(result["execution_result"])

    print("\n=== FLOW STATUS ===")
    print(result["flow_status"])


if __name__ == "__main__":
    main()