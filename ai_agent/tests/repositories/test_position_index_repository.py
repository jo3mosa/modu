from app.repositories.position_index_repository import MockPositionIndexRepository


def test_add_and_get_user_ids_by_stock() -> None:
    """
    stock_code 기준으로 user_id를 저장하고 조회할 수 있어야 한다.
    """
    repository = MockPositionIndexRepository()

    repository.add_user("005930", 1)
    repository.add_user("005930", 2)

    result = repository.get_user_ids_by_stock("005930")

    assert result == [1, 2]


def test_add_duplicate_user_id_only_saved_once() -> None:
    """
    Redis Set 구조와 동일하게 같은 user_id를 여러 번 추가해도 중복 저장되지 않아야 한다.
    """
    repository = MockPositionIndexRepository()

    repository.add_user("005930", 1)
    repository.add_user("005930", 1)

    result = repository.get_user_ids_by_stock("005930")

    assert result == [1]


def test_remove_user_id_from_stock_index() -> None:
    """
    특정 stock_code의 보유 사용자 목록에서 user_id를 제거할 수 있어야 한다.
    """
    repository = MockPositionIndexRepository()

    repository.add_user("005930", 1)
    repository.add_user("005930", 2)

    repository.remove_user("005930", 1)

    result = repository.get_user_ids_by_stock("005930")

    assert result == [2]


def test_get_user_ids_by_unknown_stock_returns_empty_list() -> None:
    """
    저장된 적 없는 stock_code를 조회하면 빈 리스트를 반환해야 한다.
    """
    repository = MockPositionIndexRepository()

    result = repository.get_user_ids_by_stock("000000")

    assert result == []