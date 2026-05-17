import './Skeleton.css';

/**
 * 로딩 placeholder 컴포넌트.
 * width/height는 px 또는 % 모두 허용. height 미지정 시 1em (텍스트 라인 높이).
 */
export default function Skeleton({ width = '100%', height = '1em', borderRadius = 4, style, className = '' }) {
  return (
    <span
      className={`skeleton ${className}`}
      style={{ width, height, borderRadius, ...style }}
      aria-hidden="true"
    />
  );
}
