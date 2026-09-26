'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState, type ReactNode } from 'react';
import { LoginPanel } from '@/components/LoginPanel';
import { Button, ErrorBox, Spinner } from '@/components/ui';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/lib/auth';

const NAV = [
  ['/admin', 'Tổng quan'],
  ['/admin/users', 'Người dùng'],
  ['/admin/orders', 'Đơn hàng'],
  ['/admin/plans', 'Gói cước'],
] as const;

/** Chặn toàn bộ /admin: phải đăng nhập + email nằm trong ADMIN_EMAILS (backend kiểm tra thật qua AdminGuard). */
export default function AdminLayout({ children }: { children: ReactNode }) {
  const { user, loading, signOut } = useAuth();
  const pathname = usePathname();
  const [state, setState] = useState<'checking' | 'ok' | 'denied' | 'error'>('checking');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!user) return;
    setState('checking');
    api('/admin/me')
      .then(() => setState('ok'))
      .catch((e: ApiError) => {
        setMessage(e.message);
        setState(e.status === 403 || e.status === 401 ? 'denied' : 'error');
      });
  }, [user]);

  let body: ReactNode;
  if (loading) body = <Spinner />;
  else if (!user) body = <LoginPanel title="Quản trị ScanX" note="Chỉ tài khoản quản trị được truy cập." />;
  else if (state === 'checking') body = <Spinner label="Đang kiểm tra quyền (máy chủ có thể mất ~1 phút để khởi động)…" />;
  else if (state === 'denied')
    body = (
      <div className="mx-auto max-w-md space-y-3">
        <ErrorBox message={`Tài khoản ${user.email} không có quyền quản trị. ${message}`} />
        <Button variant="secondary" onClick={signOut}>
          Đăng nhập tài khoản khác
        </Button>
      </div>
    );
  else if (state === 'error') body = <ErrorBox message={`Không kết nối được máy chủ: ${message}`} />;
  else body = children;

  return (
    <div className="min-h-screen">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-6">
            <Link href="/admin" className="text-lg font-bold tracking-tight">
              Scan<span className="text-accent">X</span> <span className="text-sm font-medium text-ink-3">Admin</span>
            </Link>
            {state === 'ok' && (
              <nav className="flex gap-1 text-sm">
                {NAV.map(([href, label]) => {
                  const active = href === '/admin' ? pathname === '/admin' : pathname.startsWith(href);
                  return (
                    <Link
                      key={href}
                      href={href}
                      className={`rounded-lg px-3 py-2 ${active ? 'bg-surface-2 font-medium text-ink' : 'text-ink-2 hover:bg-surface-2'}`}
                    >
                      {label}
                    </Link>
                  );
                })}
              </nav>
            )}
          </div>
          {user && (
            <div className="flex items-center gap-3 text-sm text-ink-2">
              <span className="hidden sm:inline">{user.email}</span>
              <Button variant="ghost" onClick={signOut}>
                Đăng xuất
              </Button>
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">{body}</main>
    </div>
  );
}
