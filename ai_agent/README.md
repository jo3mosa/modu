### 폴더 구조
```sh
modu-ai-agent/
├── main.py
├── app/
│   │
│   ├── graph/
│   │   ├── builder.py
│   │   └── nodes.py
│   │
│   ├── state/
│   │   └── investment_state.py
│   │
│   ├── agents/
│   │   ├── memory_agent.py
│   │   ├── strategy_agent.py
│   │   ├── critic_agent.py
│   │   ├── supervisor_agent.py
│   │   ├── risk_guard.py
│   │   ├── post_mortem_agent.py
│   │   └── rules/
│   │       └── risk_rules.py
│   │
│   ├── runtime/
│   │   └── executor.py          # LLM 미사용, 주문 실행 전용
│   │
│   ├── services/
│   │   ├── broker_service.py    # 한국투자증권 API 추상화
│   │   ├── memory_service.py    # DB/wiki CRUD 추상화
│   │   └── notification_service.py
│   │
│   ├── tools/
│   │   ├── market/
│   │   ├── memory/
│   │   ├── broker/
│   │   └── notification/
│   │
│   └── config/
│       ├── settings.py
│       ├── llm.py
│       └── prompts/
│           ├── memory.txt
│           ├── strategy.txt
│           ├── critic.txt
│           ├── supervisor.txt
│           └── post_mortem.txt
│
├── tests/
├── requirements.txt
└── README.md
```