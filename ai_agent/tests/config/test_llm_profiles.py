"""profile swap이 정확히 다른 객체를 반환하는지 검증.

LLM 객체 실제 호출은 안 함 (api 키 + 비용 회피). build 단계까지만 확인.
"""
import os

import pytest

from app.config import llm


@pytest.fixture(autouse=True)
def reset_cache_and_env(monkeypatch):
    llm._cached_llm.cache_clear()
    # 테스트 시 API 키가 있어야 builder가 동작하므로 mock 값 주입.
    monkeypatch.setenv("GMS_KEY", "test-gms-key")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key")
    monkeypatch.setenv("XAI_API_KEY", "test-xai-key")
    yield
    llm._cached_llm.cache_clear()


def test_list_profiles_contains_expected():
    names = llm.list_profiles()
    for required in ("gms_4o_mini", "claude_sonnet", "grok_2"):
        assert required in names


def test_default_profile_is_gms_mini(monkeypatch):
    monkeypatch.delenv("MODEL_PROFILE", raising=False)
    assert llm._current_profile_name() == "gms_4o_mini"
    assert llm._resolve("gms_4o_mini") == ("gms", "gpt-4o-mini")


def test_resolve_unknown_profile_raises():
    with pytest.raises(ValueError, match="정의되지 않은 profile"):
        llm._resolve("nonexistent")


def test_profile_swap_returns_different_objects(monkeypatch):
    """MODEL_PROFILE 환경변수만 바꿔도 다른 LLM 객체가 반환된다."""
    monkeypatch.setenv("MODEL_PROFILE", "gms_4o_mini")
    first = llm.get_strategy_llm()

    monkeypatch.setenv("MODEL_PROFILE", "gms_4o")
    second = llm.get_strategy_llm()

    assert first is not second


def test_same_profile_returns_cached_object(monkeypatch):
    """같은 profile 반복 호출 시 cache hit."""
    monkeypatch.setenv("MODEL_PROFILE", "gms_4o_mini")
    a = llm.get_strategy_llm()
    b = llm.get_strategy_llm()
    assert a is b


def test_missing_provider_key_raises(monkeypatch):
    pytest.importorskip("langchain_anthropic", reason="anthropic 패키지 미설치 환경에서 스킵")
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MODEL_PROFILE", "claude_sonnet")
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        llm.get_strategy_llm()


def test_builder_dispatch_by_provider(monkeypatch):
    """profile별로 올바른 provider builder가 선택되는지."""
    from langchain_openai import ChatOpenAI

    monkeypatch.setenv("MODEL_PROFILE", "gms_4o_mini")
    obj = llm.get_strategy_llm()
    assert isinstance(obj, ChatOpenAI)
