import feedparser
import requests
from bs4 import BeautifulSoup
import time
from datetime import datetime
import sqlite3 

# [TODO: 몽고DB 전환 시 상단에 import pymongo 추가 필요]

def setup_news_table(db_path="../data/stock_master.db"):
    """임시로 SQLite를 사용하며, 향후 MongoDB로 마이그레이션 할 예정입니다."""
    
    # [MongoDB 전환 예정 포인트 1: DB 연결]
    # 추후 pymongo.MongoClient(MONGO_URI)를 사용하여 연결하고,
    # db = client['modu_db'], collection = db['news'] 형태로 컬렉션을 가져오게 됩니다.
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS news_master (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source TEXT NOT NULL,
            title TEXT NOT NULL,
            link TEXT UNIQUE NOT NULL,
            content TEXT,
            published_at TEXT,
            crawled_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.commit()
    return conn

def crawl_financial_news():
    conn = setup_news_table()
    cursor = conn.cursor()

    target_sources = [
        {
            "name": "한국경제",
            "rss_url": "https://www.hankyung.com/feed/finance",
            "selector": "#articletxt"
        },
        {
            "name": "연합인포맥스",
            "rss_url": "https://news.einfomax.co.kr/rss/allArticle.xml",
            "selector": "#article-view-content-div"
        }
    ]

    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept-Language': 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7',
        'Referer': 'https://www.google.com/'
    }

    print(f"[START] 실시간 경제 뉴스 DB 적재 파이프라인 가동 ({datetime.now().strftime('%Y-%m-%d %H:%M:%S')})")

    for source in target_sources:
        print(f"\n[{source['name']}] 수집 시작")
        
        try:
            rss_res = requests.get(source['rss_url'], headers=headers)
            rss_res.raise_for_status()
            feed = feedparser.parse(rss_res.text)
        except Exception as e:
            print(f"   [FAIL] RSS 피드 접근 거부: {e}")
            continue

        saved_count = 0

        for entry in feed.entries: 
            title = entry.title
            link = entry.link
            
            # [MongoDB 전환 예정 포인트 2: 중복 검사 로직]
            # RDB의 SELECT 대신, 몽고DB에서는 collection.find_one({"link": link}) 로 교체됩니다.
            cursor.execute("SELECT 1 FROM news_master WHERE link = ?", (link,))
            if cursor.fetchone():
                continue 
                
            try:
                response = requests.get(link, headers=headers)
                response.raise_for_status()
                
                soup = BeautifulSoup(response.text, 'html.parser')
                article_body = soup.select_one(source['selector'])
                
                if article_body:
                    for script in article_body(["script", "style"]):
                        script.decompose()
                        
                    content = article_body.get_text(separator=' ', strip=True)
                    
                    print(f"   - 출처: {source['name']}")
                    print(f"   - 추출 완료: 총 {len(content)}자")

                    # [MongoDB 전환 예정 포인트 3: 데이터 적재 로직]
                    # RDB의 INSERT INTO 쿼리 대신, 몽고DB에서는 JSON(딕셔너리)을 통째로 밀어넣는 방식으로 교체됩니다.
                    # 예: collection.insert_one({"source": source['name'], "title": title, "link": link, "content": content, "published_at": entry.get('published', ''), "crawled_at": datetime.now()})
                    cursor.execute("""
                        INSERT OR IGNORE INTO news_master (source, title, link, content, published_at) 
                        VALUES (?, ?, ?, ?, ?)
                    """, (source['name'], title, link, content, entry.get('published', '')))
                    
                    saved_count += 1
                    print(f"   [DB 저장 완료] {title[:40]}...")
                
                time.sleep(1.5)
                
            except Exception as e:
                print(f"   [FAIL] 크롤링 에러: {e}")

        # [MongoDB 전환 예정 포인트 4: 트랜잭션 확정]
        # 몽고DB는 insert_one 실행 시 자동으로 적재되므로, 이 commit() 과정은 통째로 삭제됩니다.
        conn.commit()
        print(f"   {source['name']} 신규 기사 {saved_count}건 DB 적재 완료.")

    # [MongoDB 전환 예정 포인트 5: DB 연결 종료]
    # client.close() 로 대체됩니다.
    conn.close()
    print("\n[COMPLETE] 모든 뉴스 수집 및 DB 적재가 완료되었습니다.")

if __name__ == "__main__":
    crawl_financial_news()