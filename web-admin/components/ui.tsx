'use client';

import Link from 'next/link';
import type { ButtonHTMLAttributes, ReactNode } from 'react';

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  const styles = {
    primary: 'bg-accent text-accent-ink hover:opacity-90',
    secondary: 'bg-surface text-ink border border-line hover:bg-surface-2',
    danger: 'bg-surface text-bad border border-line hover:bg-surface-2',
    ghost: 'text-ink-2 hover:bg-surface-2',
  }[variant];
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${className}`}
    />
  );
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-xl border border-line bg-surface p-5 ${className}`}>{children}</div>;
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'good' | 'warn' | 'bad' | 'accent'; children: ReactNode }) {
  const styles = {
    neutral: 'bg-surface-2 text-ink-2',
    good: 'bg-surface-2 text-good',
    warn: 'bg-surface-2 text-warn',
    bad: 'bg-surface-2 text-bad',
    accent: 'bg-surface-2 text-accent',
  }[tone];
  return <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${styles}`}>{children}</span>;
}

/** Trạng thái đơn hàng: luôn kèm chữ + ký hiệu, không chỉ dựa vào màu. */
export function OrderStatus({ status }: { status: string }) {
  const map: Record<string, [string, 'good' | 'warn' | 'bad' | 'neutral', string]> = {
    paid: ['Đã thanh toán', 'good', '✓'],
    pending: ['Chờ thanh toán', 'warn', '…'],
    failed: ['Thất bại', 'bad', '✕'],
    cancelled: ['Đã huỷ', 'neutral', '–'],
  };
  const [label, tone, icon] = map[status] || [status, 'neutral', '•'];
  return (
    <Badge tone={tone}>
      <span aria-hidden>{icon}</span>
      {label}
    </Badge>
  );
}

export function Spinner({ label = 'Đang tải…' }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 py-10 text-sm text-ink-2" role="status">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-line border-t-accent" />
      {label}
    </div>
  );
}

export function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface-2 px-4 py-3 text-sm text-bad" role="alert">
      {message}
    </div>
  );
}

export function Pager({ page, pageSize, total, onChange }: { page: number; pageSize: number; total: number; onChange: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <div className="flex items-center justify-between gap-3 pt-4 text-sm text-ink-2">
      <span className="tabular">
        {total === 0 ? '0 kết quả' : `${(page - 1) * pageSize + 1}–${Math.min(page * pageSize, total)} / ${total}`}
      </span>
      <div className="flex gap-2">
        <Button variant="secondary" disabled={page <= 1} onClick={() => onChange(page - 1)}>
          ← Trước
        </Button>
        <Button variant="secondary" disabled={page >= pages} onClick={() => onChange(page + 1)}>
          Sau →
        </Button>
      </div>
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-ink-2">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-3">{hint}</span>}
    </label>
  );
}

export const inputClass =
  'w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm text-ink outline-none focus:border-accent focus:ring-2 focus:ring-accent/20';

export function SiteHeader() {
  return (
    <header className="border-b border-line bg-surface">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
        <Link href="/" className="text-lg font-bold tracking-tight">
          Scan<span className="text-accent">X</span>
        </Link>
        <nav className="flex items-center gap-1 text-sm">
          <Link href="/pricing" className="rounded-lg px-3 py-2 text-ink-2 hover:bg-surface-2">
            Bảng giá
          </Link>
          <Link href="/account" className="rounded-lg px-3 py-2 text-ink-2 hover:bg-surface-2">
            Tài khoản
          </Link>
          <Link href="/admin" className="rounded-lg px-3 py-2 text-ink-2 hover:bg-surface-2">
            Quản trị
          </Link>
        </nav>
      </div>
    </header>
  );
}
