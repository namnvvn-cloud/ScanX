'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { Badge, ErrorBox, Pager, Spinner, inputClass } from '@/components/ui';
import { businessActive, bytes, dateOnly, dateTime } from '@/lib/api';
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
}

const PAGE_SIZE = 20;

function UsersView() {
  const params = useSearchParams();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [filter, setFilter] = useState(params.get('filter') || '');
  const [page, setPage] = useState(1);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPage(1);
    }, 350);
    return () => clearTimeout(t);
  }, [q]);

  const qs = new URLSearchParams({ q: debounced, filter, page: String(page), pageSize: String(PAGE_SIZE) });
  const { data, error, loading } = useApi<{ items: UserItem[]; total: number }>(`/admin/users?${qs}`);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Người dùng</h1>
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
          <option value="">Tất cả</option>
          <option value="business">Business đang hoạt động</option>
          <option value="free">Miễn phí</option>
        </select>
      </div>

      {error && <ErrorBox message={error.message} />}
      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Gói</th>
              <th className="px-4 py-3 text-right font-medium">Tài liệu</th>
              <th className="px-4 py-3 text-right font-medium">Dung lượng</th>
              <th className="px-4 py-3 font-medium">Đăng nhập gần nhất</th>
              <th className="px-4 py-3 font-medium">Ngày tạo</th>
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
                      <Badge tone="good">✓ Business{u.business_expires_at ? ` · ${dateOnly(u.business_expires_at)}` : ''}</Badge>
                    ) : (
                      <Badge>Miễn phí</Badge>
                    )}
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
                <td colSpan={6} className="px-4 py-8 text-center text-ink-2">
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

export default function UsersPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <UsersView />
    </Suspense>
  );
}
