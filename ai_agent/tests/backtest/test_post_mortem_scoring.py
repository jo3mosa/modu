"""scorer.score_with_post_mortem 단위 검증 (LLM 미호출).

post_mortem_fn에 mock callable 주입 → reflection 객체를 직접 만들어 흐름만 본다.
"""
import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from backtest.scoring import score_with_post_mortem, _compose_decision_content


@dataclass
class _FakeReflection:
    entry_timing_assessment: str = "적절"
    exit_rule_assessment: str = "익절선 도달"
    risk_prediction_accuracy: str = "정확"
    missed_signals: list[str] = None
    lessons: list[str] = None
    summary: str = "정상 거래"

    def model_dump(self) -> dict:
        return {
            "entry_timing_assessment": self.entry_timing_assessment,
            "exit_rule_assessment": self.exit_rule_assessment,
            "risk_prediction_accuracy": self.risk_prediction_accuracy,
            "missed_signals": self.missed_signals or [],
            "lessons": self.lessons or [],
            "summary": self.summary,
        }


class _FixedPriceFetcher:
    def __init__(self, price: float) -> None:
        self.price = price

    def close_price(self, stock_code: str, target_date: date) -> float:
        return self.price


def _write_input(path: Path, lines: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for line in lines:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")


def _read_output(path: Path) -> list[dict]:
    out: list[dict] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def test_score_with_post_mortem_buy_profit(tmp_path: Path):
    """BUY 결정이 익절(가격 상승) → raw_return 양수 + post_mortem 호출됨."""
    in_path = tmp_path / "in.jsonl"
    out_path = tmp_path / "out.jsonl"
    _write_input(in_path, [{
        "date": "2025-03-10",
        "stock_code": "005930",
        "action": "trade",
        "side": "buy",
        "execution_price": 50_000,
        "bull_claim": "RSI 과매도 + 거래량 증가",
        "bear_claim": "최근 하락 추세",
        "winning_side": "BULL",
        "judgment_reason": "Bull 우세",
        "rule_ids": ["RSI-001"],
    }])

    pm_calls: list[dict] = []

    def fake_pm(**kwargs):
        pm_calls.append(kwargs)
        return _FakeReflection()

    score_with_post_mortem(
        in_path, out_path,
        price_fetcher=_FixedPriceFetcher(55_000),
        holding_days=7,
        post_mortem_fn=fake_pm,
    )

    out = _read_output(out_path)
    assert len(out) == 1
    rec = out[0]
    assert rec["exit_price"] == 55_000
    assert abs(rec["raw_return"] - 0.1) < 1e-6
    assert rec["holding_days"] == 7
    assert rec["post_mortem"] is not None
    assert rec["post_mortem"]["summary"] == "정상 거래"

    # post_mortem 호출 인자 검증
    assert len(pm_calls) == 1
    call = pm_calls[0]
    assert abs(call["raw_return"] - 0.1) < 1e-6
    assert call["alpha_return"] == 0.0
    assert call["holding_days"] == 7
    assert call["key_signals"] == ["RSI-001"]
    assert "Bull 우세" in call["decision_content"]
    assert "BULL" in call["decision_content"]


def test_score_with_post_mortem_skips_hold(tmp_path: Path):
    """HOLD 결정은 post_mortem 호출 없이 그대로 통과."""
    in_path = tmp_path / "in.jsonl"
    out_path = tmp_path / "out.jsonl"
    _write_input(in_path, [{
        "date": "2025-03-10",
        "stock_code": "005930",
        "action": "hold",
        "side": None,
    }])

    pm_calls: list[dict] = []

    def fake_pm(**kwargs):
        pm_calls.append(kwargs)
        return _FakeReflection()

    score_with_post_mortem(
        in_path, out_path,
        price_fetcher=_FixedPriceFetcher(55_000),
        holding_days=7,
        post_mortem_fn=fake_pm,
    )

    out = _read_output(out_path)
    assert len(out) == 1
    assert out[0]["raw_return"] is None
    assert out[0]["post_mortem"] is None
    assert out[0]["skip_reason"] == "hold or non-directional"
    assert len(pm_calls) == 0  # LLM 미호출 확인


def test_score_with_post_mortem_sell_inverts_return(tmp_path: Path):
    """SELL 결정은 가격 하락 시 hit, raw_return 부호 반전."""
    in_path = tmp_path / "in.jsonl"
    out_path = tmp_path / "out.jsonl"
    _write_input(in_path, [{
        "date": "2025-03-10",
        "stock_code": "005930",
        "action": "trade",
        "side": "sell",
        "execution_price": 50_000,
    }])

    score_with_post_mortem(
        in_path, out_path,
        price_fetcher=_FixedPriceFetcher(48_000),  # 가격 하락
        holding_days=7,
        post_mortem_fn=lambda **kw: _FakeReflection(),
    )

    out = _read_output(out_path)
    # 가격이 50000 → 48000으로 하락. sell이라 부호 반전 → +0.04
    assert out[0]["raw_return"] > 0
    assert abs(out[0]["raw_return"] - 0.04) < 1e-6


def test_score_with_post_mortem_handles_pm_failure(tmp_path: Path):
    """post_mortem 호출이 예외 던져도 scorer는 죽지 않음. reflection은 None."""
    in_path = tmp_path / "in.jsonl"
    out_path = tmp_path / "out.jsonl"
    _write_input(in_path, [{
        "date": "2025-03-10",
        "stock_code": "005930",
        "action": "trade",
        "side": "buy",
        "execution_price": 50_000,
    }])

    def crashing_pm(**kwargs):
        raise RuntimeError("LLM API down")

    score_with_post_mortem(
        in_path, out_path,
        price_fetcher=_FixedPriceFetcher(55_000),
        holding_days=7,
        post_mortem_fn=crashing_pm,
    )

    out = _read_output(out_path)
    assert out[0]["raw_return"] is not None  # raw_return은 계산됨
    assert out[0]["post_mortem"] is None      # reflection은 None
    assert out[0]["skip_reason"] is None      # 거래는 정상 처리


def test_compose_decision_content_includes_all_parts():
    content = _compose_decision_content({
        "judgment_reason": "Bull 우세 판단",
        "bull_claim": "RSI 과매도",
        "bear_claim": "추세 약화",
        "winning_side": "BULL",
    })
    assert "[판단 사유]" in content
    assert "[Bull 주장]" in content
    assert "[Bear 주장]" in content
    assert "[우세 관점]" in content


def test_compose_decision_content_handles_missing():
    content = _compose_decision_content({})
    assert content == "(판단 사유 없음)"
