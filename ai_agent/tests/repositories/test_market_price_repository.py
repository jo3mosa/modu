from app.repositories.market_price_repository import MockMarketPriceRepository


def test_get_returns_price_for_existing_stock() -> None:
    store = {"005930": 71000, "000660": 185000}
    repository = MockMarketPriceRepository(store=store)

    assert repository.get("005930") == 71000
    assert repository.get("000660") == 185000


def test_get_returns_none_for_unknown_stock() -> None:
    repository = MockMarketPriceRepository()

    result = repository.get("000000")

    assert result is None


def test_get_returns_none_when_store_is_empty() -> None:
    repository = MockMarketPriceRepository(store={})

    assert repository.get("005930") is None
