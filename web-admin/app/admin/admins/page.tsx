'use client';

import { useState } from 'react';
import { Badge, Button, Card, ErrorBox, Field, Spinner, inputClass } from '@/components/ui';
import { api, dateTime } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface AdminsResp {
  supers: string[];
  admins: { email: string; added_by: string | null; created_at: string }[];
}

export default function AdminsPage() {
  const me = useApi<{ email: string | null; role: 'super' | 'admin' }>('/admin/me');
  const { data, error, loading, reload } = useApi<AdminsResp>('/admin/admins');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const isSuper = me.data?.role === 'super';

  async function run(fn: () => Promise<unknown>, ok: string) {
    setBusy(true);
    setMsg(null);
    try {
      await fn();
      setMsg({ ok: true, text: ok });
      reload();
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(false);
    }
  }

  if ((loading && !data) || me.loading) return <Spinner />;
  if (error) return <ErrorBox message={error.message} />;
  if (!data) return null;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Quản trị viên</h1>
        <p className="text-sm text-ink-2">
          Quản trị viên đăng nhập web bằng <strong className="text-ink">Google</strong> đúng email được thêm. Quản trị viên phụ dùng được mọi chức năng quản
          lý, trừ thêm/gỡ quản trị viên.
        </p>
      </div>

      {isSuper && (
        <Card>
          <h2 className="font-semibold">Thêm quản trị viên</h2>
          <div className="mt-3 flex flex-wrap items-end gap-3">
            <div className="w-full max-w-sm">
              <Field label="Email (tài khoản Google)">
                <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ten@gmail.com" />
              </Field>
            </div>
            <Button
              disabled={busy || !email.includes('@')}
              onClick={() => {
                const target = email.trim();
                run(async () => {
                  await api('/admin/admins', { method: 'POST', body: JSON.stringify({ email: target }) });
                  setEmail('');
                }, `Đã thêm ${target}`);
              }}
            >
              Thêm
            </Button>
          </div>
        </Card>
      )}
      {msg && (msg.ok ? <p className="text-sm text-good">✓ {msg.text}</p> : <ErrorBox message={msg.text} />)}

      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Vai trò</th>
              <th className="px-4 py-3 font-medium">Thêm bởi</th>
              <th className="px-4 py-3 font-medium">Thời gian</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {data.supers.map((e) => (
              <tr key={e} className="border-t border-line">
                <td className="px-4 py-2.5 font-medium">{e}</td>
                <td className="px-4 py-2.5">
                  <Badge tone="accent">★ Quản trị chính</Badge>
                </td>
                <td className="px-4 py-2.5 text-ink-3" colSpan={3}>
                  Khai báo trong biến ADMIN_EMAILS trên Render (không gỡ được từ web)
                </td>
              </tr>
            ))}
            {data.admins.map((a) => (
              <tr key={a.email} className="border-t border-line">
                <td className="px-4 py-2.5 font-medium">{a.email}</td>
                <td className="px-4 py-2.5">
                  <Badge>Quản trị phụ</Badge>
                </td>
                <td className="px-4 py-2.5">{a.added_by || '—'}</td>
                <td className="px-4 py-2.5">{dateTime(a.created_at)}</td>
                <td className="px-4 py-2.5 text-right">
                  {isSuper && (
                    <Button
                      variant="ghost"
                      className="text-bad"
                      disabled={busy}
                      onClick={() => {
                        if (confirm(`Gỡ quyền quản trị của ${a.email}?`)) {
                          run(() => api(`/admin/admins/${encodeURIComponent(a.email)}`, { method: 'DELETE' }), `Đã gỡ ${a.email}`);
                        }
                      }}
                    >
                      Gỡ quyền
                    </Button>
                  )}
                </td>
              </tr>
            ))}
            {data.admins.length === 0 && (
              <tr className="border-t border-line">
                <td colSpan={5} className="px-4 py-6 text-center text-ink-2">
                  Chưa có quản trị viên phụ.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
