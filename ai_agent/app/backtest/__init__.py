"""Backtest 모듈.

과거 시점을 시뮬레이션해 그래프 본체(run_pipeline)를 재실행하고,
결정 후 N일 가격으로 채점한다.

실시간 파이프(consumer.py → run_and_publish)와 그래프를 공유하며,
state.as_of / event.as_of 주입으로 시간 인지를 분리한다.
"""
