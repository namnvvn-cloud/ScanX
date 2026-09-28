'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { Badge, ErrorBox, Pager, Spinner, inputClass } from '@/components/ui';
import { api, businessActive, bytes, dateOnly, dateTime } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface UserItem {
  id: string;
  email: string | null;
  display_name: string | null;
  is_business: boolean;
  business_expires_at: string | null;
  last_login_at: string | null;
  created_at: string;
  document_count: number;
  storage_bytes: number | string;
  device_count: number;
  app_version: string | null;
}

interface DeviceItem {
  install_id: string;
  platform: string;
  version_code: number | null;
  version_name: string | null;
  model: string | null;
  os_version: string | null;
  locale: string | null;
  user_id: string | null;
  user_email: string | null;
  open_count: number;
  first_seen: string;
  last_seen: string;
}

const PAGE_SIZE = 20;

/** Đổi gói nhanh ngay trên danh sách: tính ngày hết hạn mới (cộng dồn nếu Business còn hạn). */
const PLAN_ACTIONS: { value: string; label: string }[] = [
  { value: '', label: 'Đổi gói…' },
  { value: 'free', label: 'Chuyển về Miễn phí' },
  { value: '30', label: 'Business +1 tháng' },
  { value: '90', label: 'Business +3 tháng' },
  { value: '365', label: 'Business +1 năm' },
  { value: 'forever', label: 'Business không thời hạn' },
];

function planPayload(u: UserItem, action: string): { isBusiness: boolean; expiresAt: string | null } {
  if (action === 'free') return { isBusiness: false, expiresAt: u.business_expires_at };
  if (action === 'forever') return { isBusiness: true, expiresAt: null };
  const days = Number(action);
  const active = businessActive(u);
  // Đang Business không thời hạn → giữ không thời hạn khi cộng thêm.
  if (active && !u.business_expires_at) return { isBusiness: true, expiresAt: null };
  const base = active && u.business_expires_at ? new Date(u.business_expires_at) : new Date();
  base.setDate(base.getDate() + days);
  return { isBusiness: true, expiresAt: base.toISOString() };
}

function UsersTab() {
  const params = useSearchParams();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [filter, setFilter] = useState(params.get('filter') || '');
  const [page, setPage] = useState(1);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPage(1);
    }, 350);
    return () => clearTimeout(t);
  }, [q]);

  const qs = new URLSearchParams({ q: debounced, filter, page: String(page), pageSize: String(PAGE_SIZE) });
  const { data, error, loading, reload } = useApi<{ items: UserItem[]; total: number }>(`/admin/users?${qs}`);

  async function changePlan(u: UserItem, action: string) {
    if (!action) return;
    const label = PLAN_ACTIONS.find((a) => a.value === action)?.label ?? action;
    if (!confirm(`${label} cho ${u.email ?? u.id}?`)) return;
    setBusyId(u.id);
    setMsg(null);
    try {
      await api(`/admin/users/${u.id}/business`, { method: 'PATCH', body: JSON.stringify(planPayload(u, action)) });
      setMsg({ ok: true, text: `Đã cập nhật gói cho ${u.email ?? u.id}` });
      reload();
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-3">
        <input className={`${inputClass} max-w-sm`} placeholder="Tìm theo email / tên…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select
          className={`${inputClass} w-auto`}
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setPage(1);
          }}
        >
          <option value="">Tất cả gói</option>
          <option value="business">Business đang hoạt động</option>
          <option value="free">Miễn phí</option>
        </select>
      </div>
      {msg && (msg.ok ? <p className="text-sm text-good">✓ {msg.text}</p> : <ErrorBox message={msg.text} />)}
      {error && <ErrorBox message={error.message} />}
      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Gói</th>
              <th className="px-4 py-3 font-medium">Đổi gói</th>
              <th className="px-4 py-3 text-right font-medium">Thiết bị</th>
              <th className="px-4 py-3 text-right font-medium">Tài liệu</th>
              <th className="px-4 py-3 text-right font-medium">Dung lượng</th>
              <th className="px-4 py-3 font-medium">Đăng nhập gần nhất</th>
              <th className="px-4 py-3 font-medium">Ngày tạo</th>
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
              data?.items.map((u) => (
                <tr key={u.id} className="border-t border-line hover:bg-surface-2">
                  <td className="px-4 py-2.5">
                    <Link href={`/admin/users/${u.id}`} className="font-medium text-accent hover:underline">
                      {u.email || '(không email)'}
                    </Link>
                    {u.display_name && <div className="text-xs text-ink-3">{u.display_name}</div>}
                  </td>
                  <td className="px-4 py-2.5">
                    {businessActive(u) ? (
                      <Badge tone="good">✓ Business{u.business_expires_at ? ` · ${dateOnly(u.business_expires_at)}` : ' · ∞'}</Badge>
                    ) : (
                      <Badge>Miễn phí</Badge>
                    )}
                  </td>
                  <td className="px-4 py-2.5">
                    <select
                      aria-label={`Đổi gói cho ${u.email ?? u.id}`}
                      className="rounded-lg border border-line bg-surface px-2 py-1.5 text-sm"
                      value=""
                      disabled={busyId === u.id}
                      onChange={(e) => changePlan(u, e.target.value)}
                    >
                      {PLAN_ACTIONS.map((a) => (
                        <option key={a.value} value={a.value} disabled={a.value === ''}>
                          {busyId === u.id && a.value === '' ? 'Đang lưu…' : a.label}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="tabular px-4 py-2.5 text-right">
                    {u.device_count}
                    {u.app_version && <div className="text-xs text-ink-3">v{u.app_version}</div>}
                  </td>
                  <td className="tabular px-4 py-2.5 text-right">{u.document_count}</td>
                  <td className="tabular px-4 py-2.5 text-right">{bytes(u.storage_bytes)}</td>
                  <td className="px-4 py-2.5">{dateTime(u.last_login_at)}</td>
                  <td className="px-4 py-2.5">{dateOnly(u.created_at)}</td>
                </tr>
              ))
            )}
            {data && data.items.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-ink-2">
                  Không có người dùng phù hợp.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && <Pager page={page} pageSize={PAGE_SIZE} total={data.total} onChange={setPage} />}
    </div>
  );
}

function DevicesTab() {
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [filter, setFilter] = useState('unregistered');
  const [page, setPage] = useState(1);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPage(1);
    }, 350);
    return () => clearTimeout(t);
  }, [q]);

  const qs = new URLSearchParams({ q: debounced, filter, page: String(page), pageSize: String(PAGE_SIZE) });
  const { data, error, loading } = useApi<{ items: DeviceItem[]; total: number }>(`/admin/devices?${qs}`);

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-2">
        Mỗi lần mở app, thiết bị tự báo về (không cần đăng ký). Mã cài đặt là mã ngẫu nhiên của app — gỡ app cài lại sẽ thành mã mới.
      </p>
      <div className="flex flex-wrap gap-3">
        <input className={`${inputClass} max-w-sm`} placeholder="Tìm mã cài đặt / dòng máy / email…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select
          className={`${inputClass} w-auto`}
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setPage(1);
          }}
        >
          <option value="unregistered">Chưa đăng ký tài khoản</option>
          <option value="registered">Đã đăng nhập tài khoản</option>
          <option value="">Tất cả thiết bị</option>
        </select>
      </div>
      {error && <ErrorBox message={error.message} />}
      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Thiết bị</th>
              <th className="px-4 py-3 font-medium">Phiên bản app</th>
              <th className="px-4 py-3 font-medium">Tài khoản</th>
              <th className="px-4 py-3 text-right font-medium">Số lần mở</th>
              <th className="px-4 py-3 font-medium">Cài lần đầu</th>
              <th className="px-4 py-3 font-medium">Mở gần nhất</th>
            </tr>
          </thead>
          <tbody>
            {loading && !data ? (
              <tr>
                <td colSpan={6} className="px-4">
                  <Spinner />
                </td>
              </tr>
            ) : (
              data?.items.map((d) => (
                <tr key={d.install_id} className="border-t border-line hover:bg-surface-2">
                  <td className="px-4 py-2.5">
                    <div className="font-medium">
                      {d.model || 'Không rõ dòng máy'} <span className="text-xs uppercase text-ink-3">{d.platform}</span>
                    </div>
                    <div className="font-mono text-xs text-ink-3">
                      {d.install_id.slice(0, 13)}… {d.os_version && `· ${d.platform === 'ios' ? 'iOS' : 'Android'} ${d.os_version}`}
                    </div>
                  </td>
                  <td className="px-4 py-2.5">
                    {d.version_name ?? '—'}
                    {d.version_code != null && <span className="text-xs text-ink-3"> (build {d.version_code})</span>}
                  </td>
                  <td className="px-4 py-2.5">
                    {d.user_id ? (
                      <Link href={`/admin/users/${d.user_id}`} className="text-accent hover:underline">
                        {d.user_email ?? d.user_id.slice(0, 8)}
                      </Link>
                    ) : (
                      <Badge tone="warn">Chưa đăng ký</Badge>
                    )}
                  </td>
                  <td className="tabular px-4 py-2.5 text-right">{d.open_count}</td>
                  <td className="px-4 py-2.5">{dateTime(d.first_seen)}</td>
                  <td className="px-4 py-2.5">{dateTime(d.last_seen)}</td>
                </tr>
              ))
            )}
            {data && data.items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-ink-2">
                  Chưa có thiết bị nào — thiết bị xuất hiện khi mở app từ bản build có tính năng báo cài đặt trở đi.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && <Pager page={page} pageSize={PAGE_SIZE} total={data.total} onChange={setPage} />}
    </div>
  );
}

function UsersView() {
  const params = useSearchParams();
  const [tab, setTab] = useState<'users' | 'devices'>(params.get('tab') === 'devices' ? 'devices' : 'users');
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Người dùng</h1>
      <div role="tablist" className="flex gap-1 border-b border-line">
        {(
          [
            ['users', 'Tài khoản đã đăng ký'],
            ['devices', 'Thiết bị đã cài app'],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
              tab === key ? 'border-accent text-ink' : 'border-transparent text-ink-2 hover:text-ink'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === 'users' ? <UsersTab /> : <DevicesTab />}
    </div>
  );
}

export default function UsersPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <UsersView />
    </Suspense>
  );
}
