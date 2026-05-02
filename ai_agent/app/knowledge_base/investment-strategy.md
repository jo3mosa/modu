```yaml
decision_priority:
  - strategy
  - risk

system_trading_constraints:
  investor_type_constraints:
    active:
      max_single_stock_ratio: 20

risk_management:
  enabled: true

entry_rules:
  allow_new_position: true

position_management:
  rebalance: false

hard_constraints:
  - max_position_limit

explanation_policy:
  include_reason: true
```
