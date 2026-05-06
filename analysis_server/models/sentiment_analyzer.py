import sqlite3
import pandas as pd
import torch
import torch.nn.functional as F
import re
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from tqdm import tqdm

tqdm.pandas()

class FinancialSentimentAnalyzer:
    def __init__(self):
        print("🤖 KR-FinBERT-SC 모델 로딩 중...")
        model_name = "snunlp/KR-FinBERT-SC"
        self.tokenizer = AutoTokenizer.from_pretrained(model_name)
        self.model = AutoModelForSequenceClassification.from_pretrained(model_name)
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)
        self.model.eval()
        print(f"✅ 모델 로딩 완료! [사용 장치: {self.device}]")

    def clean_text(self, text: str) -> str:
        """기사 본문의 불필요한 노이즈(이메일, 기자 이름, 캡션, 무단전재 등) 제거"""
        if not text or pd.isna(text):
            return ""

        text = str(text)
        # 이메일 제거
        text = re.sub(r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', '', text)
        # 사진/그래픽 캡션 제거 (예: "사진=연합뉴스", "사진=게티이미지뱅크")
        text = re.sub(r'사진=\S+', '', text)
        # 코너/카테고리 태그 제거 (예: "[종목+]", "[김현석의 월스트리트나우]")
        text = re.sub(r'\[[^\]]{1,30}\]', '', text)
        # 기자 서명 제거 (예: "강경주 한경닷컴 기자")
        text = re.sub(r'[가-힣]{2,4}\s+[가-힣A-Za-z]+\s+기자', '', text)
        # 무단전재/재배포 금지 문구 제거
        text = re.sub(r'무단전재.*재배포.*금지', '', text)
        text = re.sub(r'ⓒ.*', '', text)
        # 다중 공백 정리
        text = re.sub(r'\s+', ' ', text)
        return text.strip()

    def _get_probs(self, text: str) -> tuple[float, float, float]:
        if not text:
            return (0.0, 1.0, 0.0)

        inputs = self.tokenizer(
            text, return_tensors="pt", truncation=True, max_length=512, padding=True
        )
        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            outputs = self.model(**inputs)

        probs = F.softmax(outputs.logits, dim=-1).squeeze().tolist()
        return (probs[0], probs[1], probs[2])  # neg, neu, pos

    def _chunk_and_weighted_average(self, text: str, chunk_size: int = 400, overlap: int = 50) -> tuple[float, float, float]:
        """
        역피라미드 가중치를 기본으로 하되, 청크 간 방향성이 충돌할 때만 단순 평균으로 대체.
        """
        if not text:
            return (0.0, 1.0, 0.0)

        tokens = self.tokenizer.tokenize(text)

        if len(tokens) <= chunk_size:
            return self._get_probs(text)

        chunks = []
        step = chunk_size - overlap
        for start in range(0, len(tokens), step):
            chunk_tokens = tokens[start:start + chunk_size]
            chunk_text = self.tokenizer.convert_tokens_to_string(chunk_tokens)
            chunks.append(chunk_text)
            if start + chunk_size >= len(tokens):
                break

        all_probs = [self._get_probs(chunk) for chunk in chunks]

        # 청크들이 서로 반대 방향(긍/부)을 가리키면 lead chunk의 부정어가
        # 0.8^i 가중에 의해 과대평가되는 사고를 막기 위해 균등 평균으로 폴백한다.
        chunk_scores = [p[2] - p[0] for p in all_probs]
        has_pos = any(s > 0.1 for s in chunk_scores)
        has_neg = any(s < -0.1 for s in chunk_scores)
        if has_pos and has_neg:
            weights = [1.0] * len(chunks)
        else:
            weights = [0.8 ** i for i in range(len(chunks))]

        total_weight = sum(weights)
        avg_neg = sum(p[0] * w for p, w in zip(all_probs, weights)) / total_weight
        avg_neu = sum(p[1] * w for p, w in zip(all_probs, weights)) / total_weight
        avg_pos = sum(p[2] * w for p, w in zip(all_probs, weights)) / total_weight

        return (avg_neg, avg_neu, avg_pos)

    def _probs_to_score(self, neg: float, neu: float, pos: float) -> float:
        """가희 님의 훌륭한 확신도(Confidence) 공식을 유지하되, -100 ~ 100 스케일로 직관성 부여"""
        raw_score = pos - neg
        confidence = 1.0 - neu
        
        # AI가 학습하기 좋도록 -100 ~ +100 사이의 숫자로 스케일링
        score = (raw_score * confidence) * 100
        return round(float(score), 2)

    def analyze(self, title: str = None, content: str = None) -> dict:
        """제목을 본문 앞에 prepend하여 lead chunk가 헤드라인 결론을 반영하도록 한다."""
        cleaned_title = self.clean_text(title)
        cleaned_content = self.clean_text(content)

        if cleaned_title and cleaned_content:
            combined = cleaned_title + ". " + cleaned_content
        else:
            combined = cleaned_title or cleaned_content

        if not combined:
            return {
                "sentiment_score": 0.0,
                "confidence": 0.0,
                "neg_prob": 0.0, "neu_prob": 100.0, "pos_prob": 0.0
            }

        neg, neu, pos = self._chunk_and_weighted_average(combined)
        final_score = self._probs_to_score(neg, neu, pos)

        return {
            "sentiment_score": final_score,
            "confidence": round((1.0 - neu) * 100, 2),
            "neg_prob": round(neg * 100, 2),
            "neu_prob": round(neu * 100, 2),
            "pos_prob": round(pos * 100, 2)
        }

def preview_news_sentiment():
    db_path = "../data/stock_master.db"
    csv_output_path = "../data/news_sentiment_preview.csv"

    try:
        conn = sqlite3.connect(db_path)
        query = "SELECT * FROM news_master ORDER BY published_at DESC LIMIT 100"
        df = pd.read_sql(query, conn)
        conn.close()
    except Exception as e:
        print(f"❌ DB 연결 에러: {e}")
        return

    if "content" not in df.columns:
        print("⚠️ 'content' 컬럼이 없어 분석을 중단합니다.")
        return

    analyzer = FinancialSentimentAnalyzer()
    print("\n🧠 제목+본문(가중치 적용) 감성 분석 시작...")

    results = []
    for _, row in tqdm(df.iterrows(), total=len(df)):
        title = row.get("title", "")
        content = row.get("content", "")
        result = analyzer.analyze(title=title, content=content)
        results.append(result)

    result_df = pd.DataFrame(results)
    df = pd.concat([df.reset_index(drop=True), result_df], axis=1)

    df.to_csv(csv_output_path, index=False, encoding='utf-8-sig')
    print(f"\n✅ 분석 완료! 결과가 '{csv_output_path}'에 저장되었습니다.")
    print("\n📊 감성 지수 분포 (-100 ~ 100 스케일):")
    print(df["sentiment_score"].describe())

if __name__ == "__main__":
    preview_news_sentiment()