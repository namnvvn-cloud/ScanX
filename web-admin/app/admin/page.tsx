'use client';

import Link from 'next/link';
import { DailyBars } from '@/components/DailyBars';
import { ErrorBox, Spinner } from '@/components/ui';
import { bytes, vnd } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface Stats {
  totals: {
    users: number;
    users_30d: number;
    business_active: number;
    active_7d: number;
    documents: number;
    pages: number;
    storage_bytes: number;
    orders_paid: number;
    orders_pending: number;
    revenue_total: number;
    revenue_30d: number;
  };
  series: { day: string; signups: number; documents: number; revenue: number }[];
}

const num = (n: number) => new Intl.NumberFormat('vi-VN').format(n);

function Tile({ label, value, sub, href }: { label: string; value: string; sub?: string; href?: string }) {
  const inner = (
    <>
      <div className="text-xs font-medium text-ink-2">{label}</div>
      <div className="tabular mt-1 text-2xl font-semibold">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-ink-3">{sub}</div>}
    </>
  );
  const cls = 'block rounded-xl border border-line bg-surface p-4';
  return href ? (
    <Link href={href} className={`${cls} hover:bg-surface-2`}>
      {inner}
    </Link>
  ) : (
    <div className={cls}>{inner}</div>
  );
}

export default function AdminDashboard() {
  const { data, error, loading } = useApi<Stats>('/admin/stats');
  if (loading) return <Spinner />;
  if (error) return <ErrorBox message={error.message} />;
  if (!data) return null;
  const t = data.totals;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Tổng quan</h1>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile label="Người dùng" value={num(t.users)} sub={`+${num(t.users_30d)} trong 30 ngày`} href="/admin/users" />
        <Tile label="Business đang hoạt động" value={num(t.business_active)} sub={`${t.users ? Math.round((t.business_active / t.users) * 100) : 0}% người dùng`} href="/admin/users?filter=business" />
        <Tile label="Hoạt động 7 ngày" value={num(t.active_7d)} sub="đăng nhập gần đây" />
        <Tile label="Doanh thu 30 ngày" value={vnd(t.revenue_30d)} sub={`Tổng: ${vnd(t.revenue_total)}`} href="/admin/orders?status=paid" />
        <Tile label="Tài liệu đồng bộ" value={num(t.documents)} sub={`${num(t.pages)} trang`} />
        <Tile label="Dung lượng lưu trữ" value={bytes(t.storage_bytes)} />
        <Tile label="Đơn đã thanh toán" value={num(t.orders_paid)} href="/admin/orders?status=paid" />
        <Tile label="Đơn chờ thanh toán" value={num(t.orders_pending)} href="/admin/orders?status=pending" />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <DailyBars title="Người dùng mới" data={data.series.map((s) => ({ day: s.day, value: s.signups }))} format={num} />
        <DailyBars title="Doanh thu" data={data.series.map((s) => ({ day: s.day, value: s.revenue }))} format={vnd} />
      </div>
    </div>
  );
}
