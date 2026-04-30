import { Outlet } from 'react-router-dom';

export default function AuthLayout() {
  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#0d0d0d', color: '#fff' }}>
      <Outlet />
    </div>
  );
}
