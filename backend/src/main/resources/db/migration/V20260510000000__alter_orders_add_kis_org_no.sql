-- orders: KIS 정정/취소 API 호출에 필요한 한국거래소전송주문조직번호 저장
ALTER TABLE orders ADD COLUMN kis_org_no VARCHAR;
