"""fundamental_loader

분기마다 OpenDART/KIS 재무 데이터(PER, PBR, ROE 등) 수집 → DB 적재.
변경 빈도가 낮으므로 Redis가 아닌 DB에 영속 저장.
"""


def run() -> None:
    raise NotImplementedError("fundamental_loader.run is not implemented yet")


if __name__ == "__main__":
    run()
