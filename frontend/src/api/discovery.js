import apiClient from './apiClient';

/**
 * 등급별 추천 종목을 조회합니다.
 * @param {Object} params
 * @param {number} [params.perTier=5] - tier당 반환할 최대 종목 수
 * @returns {Promise<Object>} API 응답 데이터 (user, recommendedAt, totalCount, tierCounts, tiers)
 */
export async function getRecommendations(params = {}) {
  const { perTier = 5 } = params;
  const response = await apiClient(`/recommendations?perTier=${perTier}`, {
    method: 'GET',
  });
  return response.data; // { user, recommendedAt, totalCount, tierCounts, tiers } 반환
}
