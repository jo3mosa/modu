from app.repositories.portfolio_snapshot_repository import MockPortfolioSnapshotRepository


def test_get_returns_snapshot_for_existing_user() -> None:
    store = {
        1: {
            "cash_balance": 1_000_000,
            "total_assets": 5_000_000,
            "holdings": [
                {
                    "stock_code": "005930",
                    "stock_name": "삼성전자",
                    "quantity": 10,
                    "average_price": 75000,
                }
            ],
        }
    }
    repository = MockPortfolioSnapshotRepository(store=store)

    result = repository.get(1)

    assert result["cash_balance"] == 1_000_000
    assert result["total_assets"] == 5_000_000
    assert len(result["holdings"]) == 1
    assert result["holdings"][0]["stock_code"] == "005930"


def test_get_returns_empty_dict_for_unknown_user() -> None:
    repository = MockPortfolioSnapshotRepository()

    result = repository.get(999)

    assert result == {}


def test_get_returns_independent_snapshots_per_user() -> None:
    store = {
        1: {"cash_balance": 1_000_000, "total_assets": 2_000_000, "holdings": []},
        2: {"cash_balance": 500_000, "total_assets": 800_000, "holdings": []},
    }
    repository = MockPortfolioSnapshotRepository(store=store)

    assert repository.get(1)["cash_balance"] == 1_000_000
    assert repository.get(2)["cash_balance"] == 500_000
