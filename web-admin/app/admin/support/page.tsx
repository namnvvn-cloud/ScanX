'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Badge, Button, ErrorBox, Pager, Spinner, inputClass } from '@/components/ui';
import { api, dateTime } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface Ticket {
  id: string;
  user_id: string | null;
  user_email: string | null;
  email: string | null;
  subject: string;
  message: string;
  platform: string | null;
  app_version: string | null;
  device: string | null;
  priority: boolean;
  status: 'open' | 'closed';
  admin_note: string | null;
  created_at: string;
}

function TicketCard({ t, onChanged }: { t: Ticket; onChanged: () => void }) {
  const [note, setNote] = useState(t.admin_note ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const contact = t.email || t.user_email;

  async function save(status?: 'open' | 'closed') {
    setBusy(true);
    setErr(null);
    try {
      await api(`/admin/support/${t.id}`, { method: 'PATCH', body: JSON.stringify({ adminNote: note, ...(status ? { status } : {}) }) });
      onChanged();
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`rounded-xl border bg-surface p-5 ${t.priority && t.status === 'open' ? 'border-accent' : 'border-line'}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="font-semibold">{t.subject}</h2>
          <p className="text-xs text-ink-3">
            {dateTime(t.created_at)} · {t.platform ?? '—'} {t.app_version ? `v${t.app_version}` : ''} {t.device ? `· ${t.device}` : ''}
          </p>
        </div>
        <div className="flex gap-2">
          {t.priority && <Badge tone="accent">★ Ưu tiên (Business)</Badge>}
          {t.status === 'open' ? <Badge tone="warn">● Đang mở</Badge> : <Badge tone="good">✓ Đã xử lý</Badge>}
        </div>
      </div>
      <p className="mt-3 whitespace-pre-wrap text-sm">{t.message}</p>
      <p className="mt-3 text-sm text-ink-2">
        Liên hệ:{' '}
        {contact ? (
          <a className="text-accent hover:underline" href={`mailto:${contact}?subject=${encodeURIComponent('Re: ' + t.subject)}`}>
            {contact}
          </a>
        ) : (
          'không để lại email'
        )}
        {t.user_id && (
          <>
            {' · '}
            <Link href={`/admin/users/${t.user_id}`} className="text-accent hover:underline">
              Xem tài khoản
            </Link>
          </>
        )}
      </p>
      <textarea
        className={`${inputClass} mt-3`}
        rows={2}
        placeholder="Ghi chú xử lý (chỉ admin thấy)…"
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />
      {err && <div className="mt-2"><ErrorBox message={err} /></div>}
      <div className="mt-3 flex flex-wrap gap-2">
        {t.status === 'open' ? (
          <Button disabled={busy} onClick={() => save('closed')}>
            Đánh dấu đã xử lý
          </Button>
        ) : (
          <Button variant="secondary" disabled={busy} onClick={() => save('open')}>
            Mở lại
          </Button>
        )}
        <Button variant="secondary" disabled={busy || note === (t.admin_note ?? '')} onClick={() => save()}>
          Lưu ghi chú
        </Button>
      </div>
    </div>
  );
}

export default function SupportPage() {
  const [status, setStatus] = useState('open');
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useApi<{ items: Ticket[]; total: number; pageSize: number }>(`/admin/support?status=${status}&page=${page}`);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">Hỗ trợ khách hàng</h1>
        <p className="text-sm text-ink-2">Yêu cầu gửi từ app. Tài khoản Business còn hạn được xếp lên đầu (ưu tiên). Trả lời khách qua email.</p>
      </div>
      <select
        className={`${inputClass} w-auto`}
        value={status}
        onChange={(e) => {
          setStatus(e.target.value);
          setPage(1);
        }}
      >
        <option value="open">Đang mở</option>
        <option value="closed">Đã xử lý</option>
        <option value="all">Tất cả</option>
      </select>
      {error && <ErrorBox message={error.message} />}
      {loading && !data && <Spinner />}
      <div className="space-y-3">
        {data?.items.map((t) => (
          <TicketCard key={t.id + t.status + (t.admin_note ?? '')} t={t} onChanged={reload} />
        ))}
        {data && data.items.length === 0 && <p className="py-8 text-center text-ink-2">Không có yêu cầu nào.</p>}
      </div>
      {data && <Pager page={page} pageSize={data.pageSize} total={data.total} onChange={setPage} />}
    </div>
  );
}
