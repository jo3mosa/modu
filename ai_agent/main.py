from app.graph.builder import build_investment_graph
from app.state.investment_state import InvestmentAgentState


def main() -> None:
    graph = build_investment_graph()

    initial_state = InvestmentAgentState(
        market_snapshot={
            "index": "KOSPI",
            "market_status": "open",
            "volatility": "normal",
        },
        signals=[
            {
                "symbol": "005930",
                "signal": "golden_cross",
                "rsi": 48,
                "sentiment_score": 0.67,
            }
        ],
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