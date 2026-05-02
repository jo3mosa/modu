import re
from pathlib import Path
from typing import Any

import yaml


class KnowledgeBaseLoader:
    def __init__(self, base_dir: str | Path = "app/knowledge_base") -> None:
        self.base_dir = Path(base_dir)

    def load_markdown(self, file_name: str) -> str:
        path = self.base_dir / file_name

        if not path.exists():
            raise FileNotFoundError(f"Knowledge base file not found: {path}")

        return path.read_text(encoding="utf-8")

    def load_yaml_sections(self, file_name: str) -> dict[str, Any]:
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
        pattern = r"^```yaml\s*\n(.*?)^```"
        return re.findall(pattern, markdown, flags=re.DOTALL | re.MULTILINE)

    @staticmethod
    def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
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