"""Backtest 실행 중 OS sleep 방지.

backtest는 수 시간 단위로 돌아가는데, 사용자가 노트북 뚜껑을 닫거나 PC가 절전
모드로 빠지면 백그라운드 프로세스도 일시정지된다. 이 모듈은 backtest가 실행 중인
동안 OS sleep을 막는 context manager를 제공한다.

Windows: SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED) 호출.
mac/linux: 현재 no-op — 필요 시 caffeinate / systemd-inhibit으로 확장 가능.

사용:
    from ai_agent.backtest.keep_awake import keep_awake

    with keep_awake(reason="backtest mode=debate_2"):
        run(...)
"""
from __future__ import annotations

import atexit
import contextlib
import logging
import platform
from typing import Iterator

logger = logging.getLogger(__name__)


# Windows SetThreadExecutionState 플래그
# ref: https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate
_ES_CONTINUOUS = 0x80000000
_ES_SYSTEM_REQUIRED = 0x00000001
_ES_DISPLAY_REQUIRED = 0x00000002  # 화면도 안 꺼지게 하려면 OR. 현재는 시스템 sleep만 막음.


@contextlib.contextmanager
def keep_awake(reason: str = "backtest") -> Iterator[None]:
    """OS sleep / hibernate 방지 context manager.

    Windows: SetThreadExecutionState로 시스템 sleep만 막음 (화면은 꺼져도 됨).
    그 외 OS: 로깅만 하고 no-op.

    with 블록 종료 시 자동 해제. 예외 발생해도 finally로 해제 보장.
    """
    if platform.system() != "Windows":
        logger.info("keep_awake: %s OS — no-op (%s)", platform.system(), reason)
        yield
        return

    import ctypes
    flags = _ES_CONTINUOUS | _ES_SYSTEM_REQUIRED
    try:
        prev = ctypes.windll.kernel32.SetThreadExecutionState(flags)
    except Exception:
        logger.exception("keep_awake: SetThreadExecutionState 호출 실패 — sleep 방지 불가")
        yield
        return

    if prev == 0:
        logger.warning("keep_awake: SetThreadExecutionState가 0 반환 — sleep 방지 안 됨 (%s)", reason)
    else:
        logger.info("keep_awake: Windows 시스템 sleep 방지 ON (%s)", reason)

    try:
        yield
    finally:
        try:
            # ES_CONTINUOUS만 set하면 이전 요청 해제 (이전 상태로 복귀)
            ctypes.windll.kernel32.SetThreadExecutionState(_ES_CONTINUOUS)
            logger.info("keep_awake: Windows sleep 방지 해제")
        except Exception:
            logger.exception("keep_awake: 해제 실패 (시스템은 다음 idle에 정상 sleep)")


def enable_keep_awake_at_exit(reason: str = "backtest") -> None:
    """프로세스 종료(atexit)까지 sleep 방지 유지.

    with-block 들여쓰기 없이 main() 한 줄로 활성화. 정상 종료 / sys.exit /
    예외 / SIGTERM 어떤 경로든 atexit hook이 해제 호출. backtest처럼 본문이 길고
    return path가 여러 군데인 main에 적합.
    """
    ctx = keep_awake(reason=reason)
    ctx.__enter__()
    atexit.register(ctx.__exit__, None, None, None)
