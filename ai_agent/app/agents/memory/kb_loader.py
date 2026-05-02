import re
from pathlib import Path
from typing import Any

import yaml


class KnowledgeBaseLoader:
    """
    Knowledge Base Markdown 파일을 읽고 파싱하는 로더.

    역할:
    - app/knowledge_base 아래의 md 파일을 읽는다.
    - md 파일 안의 ```yaml 코드블록을 찾아 dict로 변환한다.
    - 여러 yaml 블록이 있으면 하나의 dict로 병합한다.

    사용 대상:
    - investment-profile.md
    - investment-strategy.md
    - trade-history-wiki.md
    - llm-wiki.md
    """
    def __init__(self, base_dir: str | Path = "app/knowledge_base") -> None:
        self.base_dir = Path(base_dir)

    def load_markdown(self, file_name: str) -> str:
        """
        Markdown 파일 원문을 그대로 읽는다.

        trade-history-wiki.md, llm-wiki.md처럼
        문서 전체 또는 특정 섹션을 LLM context로 넣을 때 사용한다.
        """

        path = self.base_dir / file_name

        if not path.exists():
            raise FileNotFoundError(f"Knowledge base file not found: {path}")

        return path.read_text(encoding="utf-8")

    def load_yaml_sections(self, file_name: str) -> dict[str, Any]:
        """
        Markdown 파일 안의 yaml 코드블록들을 모두 추출해서 dict로 병합한다.
        """

        markdown = self.load_markdown(file_name)
        sections = self._extract_yaml_code_blocks(markdown)

        merged: dict[str, Any] = {}

        for section in sections:
            try:
                parsed = yaml.safe_load(section) or {}
            except yaml.YAMLError:
                continue

            if isinstance(parsed, dict):
                merged = self._deep_merge(merged, parsed)

        return merged

    @staticmethod
    def _extract_yaml_code_blocks(markdown: str) -> list[str]:
        """
        Markdown에서 ```yaml 로 시작하고 ```로 끝나는 코드블록만 추출한다.
        """

        pattern = r"^```yaml\s*\n(.*?)^```"
        return re.findall(pattern, markdown, flags=re.DOTALL | re.MULTILINE)

    @staticmethod
    def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
        """
        두 dict를 깊게 병합한다.

        base:
        {
            "risk_rules": {
                "stop_loss": {...}
            }
        }

        override:
        {
            "risk_rules": {
                "take_profit": {...}
            }
        }

        결과:
        {
            "risk_rules": {
                "stop_loss": {...},
                "take_profit": {...}
            }
        }
        """

        result = base.copy()

        for key, value in override.items():
            if (
                key in result
                and isinstance(result[key], dict)
                and isinstance(value, dict)
            ):
                result[key] = KnowledgeBaseLoader._deep_merge(result[key], value)
            else:
                result[key] = value

        return result