'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { Button, ErrorBox, OrderStatus, Pager, Spinner, inputClass } from '@/components/ui';
import { api, dateTime, vnd } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface OrderItem {
  id: string;
  code: string;
  user_id: string;
  user_email: string | null;
  plan_name: string | null;
  plan_id: string;
  amount_vnd: number;
  provider: string;
  status: string;
  provider_txn_id: string | null;
  created_at: string;
  paid_at: string | null;
}

const PAGE_SIZE = 20;

function OrdersView() {
  const params = useSearchParams();
  const [status, setStatus] = useState(params.get('status') || '');
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(1);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPage(1);
    }, 350);
    return () => clearTimeout(t);
  }, [q]);

  const qs = new URLSearchParams({ status, q: debounced, page: String(page), pageSize: String(PAGE_SIZE) });
  const { data, error, loading, reload } = useApi<{ items: OrderItem[]; total: number }>(`/admin/orders?${qs}`);

  async function act(o: OrderItem, action: 'confirm' | 'cancel') {
    const text =
      action === 'confirm'
        ? `Xác nhận đã nhận ${vnd(o.amount_vnd)} cho đơn ${o.code}? Business sẽ được kích hoạt/gia hạn cho ${o.user_email}.`
        : `Huỷ đơn ${o.code}?`;
    if (!confirm(text)) return;
    setBusy(o.id);
    setMsg(null);
    try {
      await api(`/admin/orders/${o.id}/${action}`, { method: 'POST' });
      reload();
    } catch (e: any) {
      setMsg(e.message);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Đơn hàng</h1>
      <div className="flex flex-wrap gap-3">
        <input className={`${inputClass} max-w-sm`} placeholder="Mã đơn / email…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select
          className={`${inputClass} w-auto`}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(1);
          }}
        >
          <option value="">Mọi trạng thái</option>
          <option value="pending">Chờ thanh toán</option>
          <option value="paid">Đã thanh toán</option>
          <option value="failed">Thất bại</option>
          <option value="cancelled">Đã huỷ</option>
        </select>
      </div>
      {(error || msg) && <ErrorBox message={msg || error!.message} />}

      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Mã đơn</th>
              <th className="px-4 py-3 font-medium">Khách hàng</th>
              <th className="px-4 py-3 font-medium">Gói</th>
              <th className="px-4 py-3 font-medium">Cổng</th>
              <th className="px-4 py-3 text-right font-medium">Số tiền</th>
              <th className="px-4 py-3 font-medium">Thời gian</th>
              <th className="px-4 py-3 font-medium">Trạng thái</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {loading && !data ? (
              <tr>
                <td colSpan={8} className="px-4">
                  <Spinner />
                </td>
              </tr>
            ) : (
              data?.items.map((o) => (
                <tr key={o.id} className="border-t border-line hover:bg-surface-2">
                  <td className="px-4 py-2.5 font-mono text-xs">
                    {o.code}
                    {o.provider_txn_id && <div className="text-ink-3">GD: {o.provider_txn_id}</div>}
                  </td>
                  <td className="px-4 py-2.5">
                    <Link href={`/admin/users/${o.user_id}`} className="text-accent hover:underline">
                      {o.user_email || o.user_id.slice(0, 8)}
                    </Link>
                  </td>
                  <td className="px-4 py-2.5">{o.plan_name || o.plan_id}</td>
                  <td className="px-4 py-2.5 uppercase">{o.provider}</td>
                  <td className="tabular px-4 py-2.5 text-right">{vnd(o.amount_vnd)}</td>
                  <td className="px-4 py-2.5">
                    {dateTime(o.created_at)}
                    {o.paid_at && <div className="text-xs text-ink-3">TT: {dateTime(o.paid_at)}</div>}
                  </td>
                  <td className="px-4 py-2.5">
                    <OrderStatus status={o.status} />
                  </td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-right">
                    {o.status === 'pending' && (
                      <>
                        <Button variant="ghost" disabled={busy === o.id} onClick={() => act(o, 'confirm')}>
                          Xác nhận
                        </Button>
                        <Button variant="ghost" className="text-bad" disabled={busy === o.id} onClick={() => act(o, 'cancel')}>
                          Huỷ
                        </Button>
                      </>
                    )}
                  </td>
                </tr>
              ))
            )}
            {data && data.items.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-ink-2">
                  Không có đơn hàng.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && <Pager page={page} pageSize={PAGE_SIZE} total={data.total} onChange={setPage} />}
      <p className="text-xs text-ink-3">
        “Xác nhận” dùng khi khách chuyển khoản thủ công hoặc IPN bị lỡ — kiểm tra tiền đã về tài khoản trước khi bấm.
      </p>
    </div>
  );
}

export default function OrdersPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <OrdersView />
    </Suspense>
  );
}
