import os

from langchain_openai import ChatOpenAI

strategy_llm = ChatOpenAI(
    model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
    temperature=0.2,
)
