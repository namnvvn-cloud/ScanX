'use client';

import { useEffect, useState } from 'react';
import { Badge, Button, Card, ErrorBox, Field, Spinner, inputClass } from '@/components/ui';
import { api, dateTime } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface Plan {
  id: string;
  name: string;
  description: string | null;
  price_vnd: number;
  duration_days: number;
  active: boolean;
  updated_at: string;
}

function PlanEditor({ plan, onSaved }: { plan: Plan; onSaved: () => void }) {
  const [f, setF] = useState({ name: '', description: '', price: '', days: '', active: true });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    setF({
      name: plan.name,
      description: plan.description || '',
      price: String(plan.price_vnd),
      days: String(plan.duration_days),
      active: plan.active,
    });
  }, [plan]);

  const price = Number(f.price);
  const days = Number(f.days);
  const valid = f.name.trim() && Number.isInteger(price) && price >= 0 && Number.isInteger(days) && days >= 1;

  async function save() {
    setBusy(true);
    setMsg(null);
    try {
      await api(`/admin/plans/${plan.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ name: f.name.trim(), description: f.description.trim(), priceVnd: price, durationDays: days, active: f.active }),
      });
      setMsg({ ok: true, text: 'Đã lưu' });
      onSaved();
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <div className="flex items-center justify-between gap-3">
        <span className="font-mono text-xs text-ink-3">{plan.id}</span>
        {plan.active ? <Badge tone="good">✓ Đang bán</Badge> : <Badge>Ẩn</Badge>}
      </div>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <Field label="Tên gói">
          <input className={inputClass} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
        </Field>
        <Field label="Giá (VNĐ)">
          <input className={`${inputClass} tabular`} inputMode="numeric" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value.replace(/\D/g, '') })} />
        </Field>
        <Field label="Thời hạn (ngày)">
          <input className={`${inputClass} tabular`} inputMode="numeric" value={f.days} onChange={(e) => setF({ ...f, days: e.target.value.replace(/\D/g, '') })} />
        </Field>
        <label className="flex items-center gap-2 self-end pb-2 text-sm font-medium">
          <input type="checkbox" className="h-4 w-4 accent-[var(--accent)]" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />
          Hiển thị trên trang Bảng giá
        </label>
        <div className="sm:col-span-2">
          <Field label="Mô tả">
            <textarea className={inputClass} rows={2} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
          </Field>
        </div>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Button disabled={busy || !valid} onClick={save}>
          Lưu
        </Button>
        {msg && <span className={`text-sm ${msg.ok ? 'text-good' : 'text-bad'}`}>{msg.ok ? `✓ ${msg.text}` : msg.text}</span>}
        <span className="ml-auto text-xs text-ink-3">Cập nhật {dateTime(plan.updated_at)}</span>
      </div>
    </Card>
  );
}

export default function PlansPage() {
  const { data, error, loading, reload } = useApi<Plan[]>('/admin/plans');
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">Gói cước</h1>
        <p className="text-sm text-ink-2">Đổi giá chỉ áp dụng cho đơn tạo sau; đơn cũ giữ nguyên số tiền & thời hạn.</p>
      </div>
      {loading && !data && <Spinner />}
      {error && <ErrorBox message={error.message} />}
      <div className="grid gap-4 lg:grid-cols-2">
        {data?.map((p) => (
          <PlanEditor key={p.id} plan={p} onSaved={reload} />
        ))}
      </div>
    </div>
  );
}
