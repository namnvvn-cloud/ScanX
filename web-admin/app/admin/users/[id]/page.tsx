'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Badge, Button, Card, ErrorBox, Field, OrderStatus, Spinner, inputClass } from '@/components/ui';
import { api, businessActive, bytes, dateOnly, dateTime, vnd } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface Detail {
  user: {
    id: string;
    email: string | null;
    display_name: string | null;
    firebase_uid: string;
    is_business: boolean;
    business_expires_at: string | null;
    last_login_at: string | null;
    created_at: string;
  };
  documents: { id: string; title: string; page_count: number; file_size: number | null; created_at: string }[];
  orders: { id: string; code: string; plan_id: string; amount_vnd: number; provider: string; status: string; created_at: string; paid_at: string | null }[];
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, reload } = useApi<Detail>(`/admin/users/${id}`);
  const [isBusiness, setIsBusiness] = useState(false);
  const [expires, setExpires] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (!data) return;
    setIsBusiness(data.user.is_business);
    setExpires(data.user.business_expires_at ? data.user.business_expires_at.slice(0, 10) : '');
  }, [data]);

  async function run(fn: () => Promise<unknown>, okText: string) {
    setBusy(true);
    setMsg(null);
    try {
      await fn();
      setMsg({ ok: true, text: okText });
      reload();
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(false);
    }
  }

  function addDays(days: number) {
    const base = expires && new Date(expires) > new Date() ? new Date(expires) : new Date();
    base.setDate(base.getDate() + days);
    setIsBusiness(true);
    setExpires(base.toISOString().slice(0, 10));
  }

  async function download(docId: string) {
    try {
      const r = await api<{ downloadUrl: string }>(`/admin/documents/${docId}/download`);
      window.open(r.downloadUrl, '_blank', 'noopener');
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    }
  }

  if (loading && !data) return <Spinner />;
  if (error) return <ErrorBox message={error.message} />;
  if (!data) return null;
  const u = data.user;

  return (
    <div className="space-y-6">
      <div>
        <Link href="/admin/users" className="text-sm text-ink-2 hover:underline">
          ← Người dùng
        </Link>
        <h1 className="mt-1 text-2xl font-bold">{u.email || '(không email)'}</h1>
        <p className="text-sm text-ink-2">
          {u.display_name && `${u.display_name} · `}Tạo {dateOnly(u.created_at)} · Đăng nhập gần nhất {dateTime(u.last_login_at)}
        </p>
        <p className="mt-1 font-mono text-xs text-ink-3">UID: {u.firebase_uid}</p>
      </div>

      {msg && (msg.ok ? <p className="text-sm text-good">✓ {msg.text}</p> : <ErrorBox message={msg.text} />)}

      <Card>
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-semibold">Gói Business</h2>
          {businessActive(u) ? <Badge tone="good">✓ Đang hoạt động</Badge> : <Badge>Miễn phí</Badge>}
        </div>
        <div className="mt-4 grid gap-4 sm:grid-cols-[auto_1fr] sm:items-end">
          <label className="flex items-center gap-2 text-sm font-medium">
            <input type="checkbox" className="h-4 w-4 accent-[var(--accent)]" checked={isBusiness} onChange={(e) => setIsBusiness(e.target.checked)} />
            Bật Business
          </label>
          <Field label="Hết hạn" hint="Để trống = không thời hạn">
            <input type="date" className={`${inputClass} max-w-xs`} value={expires} disabled={!isBusiness} onChange={(e) => setExpires(e.target.value)} />
          </Field>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => addDays(30)}>
            +30 ngày
          </Button>
          <Button variant="secondary" onClick={() => addDays(365)}>
            +1 năm
          </Button>
          <Button
            disabled={busy}
            onClick={() =>
              run(
                () =>
                  api(`/admin/users/${u.id}/business`, {
                    method: 'PATCH',
                    body: JSON.stringify({
                      isBusiness,
                      // 23:59:59 giờ VN của ngày chọn
                      expiresAt: isBusiness && expires ? new Date(`${expires}T23:59:59+07:00`).toISOString() : null,
                    }),
                  }),
                'Đã lưu gói Business',
              )
            }
          >
            Lưu
          </Button>
        </div>
      </Card>

      <Card>
        <h2 className="font-semibold">Tài liệu đồng bộ ({data.documents.length})</h2>
        {data.documents.length === 0 ? (
          <p className="mt-2 text-sm text-ink-2">Chưa có tài liệu.</p>
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-ink-3">
                <tr>
                  <th className="py-2 pr-4 font-medium">Tên</th>
                  <th className="py-2 pr-4 text-right font-medium">Trang</th>
                  <th className="py-2 pr-4 text-right font-medium">Dung lượng</th>
                  <th className="py-2 pr-4 font-medium">Ngày tạo</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {data.documents.map((d) => (
                  <tr key={d.id} className="border-t border-line">
                    <td className="py-2 pr-4">{d.title}</td>
                    <td className="tabular py-2 pr-4 text-right">{d.page_count}</td>
                    <td className="tabular py-2 pr-4 text-right">{d.file_size ? bytes(d.file_size) : '—'}</td>
                    <td className="py-2 pr-4">{dateTime(d.created_at)}</td>
                    <td className="py-2 text-right whitespace-nowrap">
                      <Button variant="ghost" onClick={() => download(d.id)}>
                        Tải
                      </Button>
                      <Button
                        variant="ghost"
                        className="text-bad"
                        disabled={busy}
                        onClick={() => {
                          if (confirm(`Xoá vĩnh viễn "${d.title}" (cả file trên storage)?`))
                            run(() => api(`/admin/documents/${d.id}`, { method: 'DELETE' }), 'Đã xoá tài liệu');
                        }}
                      >
                        Xoá
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card>
        <h2 className="font-semibold">Đơn hàng ({data.orders.length})</h2>
        {data.orders.length === 0 ? (
          <p className="mt-2 text-sm text-ink-2">Chưa có đơn hàng.</p>
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-ink-3">
                <tr>
                  <th className="py-2 pr-4 font-medium">Mã</th>
                  <th className="py-2 pr-4 font-medium">Gói</th>
                  <th className="py-2 pr-4 font-medium">Cổng</th>
                  <th className="py-2 pr-4 text-right font-medium">Số tiền</th>
                  <th className="py-2 pr-4 font-medium">Tạo lúc</th>
                  <th className="py-2 font-medium">Trạng thái</th>
                </tr>
              </thead>
              <tbody>
                {data.orders.map((o) => (
                  <tr key={o.id} className="border-t border-line">
                    <td className="py-2 pr-4 font-mono text-xs">{o.code}</td>
                    <td className="py-2 pr-4">{o.plan_id}</td>
                    <td className="py-2 pr-4 uppercase">{o.provider}</td>
                    <td className="tabular py-2 pr-4 text-right">{vnd(o.amount_vnd)}</td>
                    <td className="py-2 pr-4">{dateTime(o.created_at)}</td>
                    <td className="py-2">
                      <OrderStatus status={o.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
