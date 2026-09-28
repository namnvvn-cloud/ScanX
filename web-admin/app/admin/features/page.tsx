'use client';

import { useEffect, useState } from 'react';
import { Button, ErrorBox, Spinner } from '@/components/ui';
import { api } from '@/lib/api';
import { useApi } from '@/lib/useApi';

type Access = 'free' | 'business' | 'off';
interface Feature {
  key: string;
  label: string;
  access: Access;
  trials: number;
}
interface Catalog {
  core: { key: string; label: string }[];
  features: Feature[];
}

/**
 * Checklist quyền tính năng: tích "Miễn phí" = ai cũng dùng (Business tự có); chỉ tích "Business" = cần gói
 * (Free được N lượt thử / máy); bỏ cả hai = tạm tắt tính năng với mọi người.
 */
export default function FeaturesPage() {
  const { data, error, loading, reload } = useApi<Catalog>('/admin/features');
  const [rows, setRows] = useState<Feature[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (data) setRows(data.features);
  }, [data]);

  const dirty = data ? JSON.stringify(rows) !== JSON.stringify(data.features) : false;

  function update(key: string, change: { free?: boolean; business?: boolean; trials?: number }) {
    setRows((prev) =>
      prev.map((f) => {
        if (f.key !== key) return f;
        let free = f.access === 'free';
        let business = f.access !== 'off';
        if (change.free !== undefined) {
          free = change.free;
          if (free) business = true; // Miễn phí thì Business đương nhiên có.
        }
        if (change.business !== undefined) {
          business = change.business;
          if (!business) free = false;
        }
        const access: Access = free ? 'free' : business ? 'business' : 'off';
        const trials = change.trials !== undefined ? change.trials : f.trials;
        return { ...f, access, trials: access === 'business' ? trials : 0 };
      }),
    );
  }

  async function save() {
    setBusy(true);
    setMsg(null);
    try {
      const policy = Object.fromEntries(rows.map((f) => [f.key, { access: f.access, trials: f.trials }]));
      await api('/admin/features', { method: 'PUT', body: JSON.stringify({ policy }) });
      setMsg({ ok: true, text: 'Đã lưu — app áp dụng ngay lần mở / quay lại tiếp theo (tối đa ~1 phút)' });
      reload();
    } catch (e: any) {
      setMsg({ ok: false, text: e.message });
    } finally {
      setBusy(false);
    }
  }

  if (loading && !data) return <Spinner />;
  if (error) return <ErrorBox message={error.message} />;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">Tính năng Miễn phí / Business</h1>
        <p className="text-sm text-ink-2">
          Tích chọn gói được dùng từng tính năng. Áp dụng cho app Android, iOS và bảng so sánh trên trang Bảng giá — không cần phát hành lại app.
        </p>
      </div>

      <div className="overflow-x-auto rounded-xl border border-line bg-surface">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-3">
            <tr>
              <th className="px-4 py-3 font-medium">Tính năng</th>
              <th className="px-4 py-3 text-center font-medium">Miễn phí</th>
              <th className="px-4 py-3 text-center font-medium">Business</th>
              <th className="px-4 py-3 text-center font-medium">Lượt thử cho Free</th>
              <th className="px-4 py-3 font-medium">Trạng thái</th>
            </tr>
          </thead>
          <tbody>
            {data.core.map((c) => (
              <tr key={c.key} className="border-t border-line text-ink-2">
                <td className="px-4 py-2.5">{c.label}</td>
                <td className="px-4 py-2.5 text-center">
                  <input type="checkbox" checked disabled aria-label="Miễn phí (cố định)" className="h-4 w-4" />
                </td>
                <td className="px-4 py-2.5 text-center">
                  <input type="checkbox" checked disabled aria-label="Business (cố định)" className="h-4 w-4" />
                </td>
                <td className="px-4 py-2.5 text-center text-ink-3">—</td>
                <td className="px-4 py-2.5 text-xs text-ink-3">Tính năng lõi — luôn miễn phí</td>
              </tr>
            ))}
            {rows.map((f) => (
              <tr key={f.key} className="border-t border-line">
                <td className="px-4 py-2.5">{f.label}</td>
                <td className="px-4 py-2.5 text-center">
                  <input
                    type="checkbox"
                    aria-label={`Miễn phí: ${f.label}`}
                    className="h-4 w-4 accent-[var(--accent)]"
                    checked={f.access === 'free'}
                    onChange={(e) => update(f.key, { free: e.target.checked })}
                  />
                </td>
                <td className="px-4 py-2.5 text-center">
                  <input
                    type="checkbox"
                    aria-label={`Business: ${f.label}`}
                    className="h-4 w-4 accent-[var(--accent)]"
                    checked={f.access !== 'off'}
                    onChange={(e) => update(f.key, { business: e.target.checked })}
                  />
                </td>
                <td className="px-4 py-2.5 text-center">
                  {f.access === 'business' ? (
                    <input
                      aria-label={`Lượt thử: ${f.label}`}
                      className="tabular w-16 rounded-lg border border-line bg-surface px-2 py-1 text-center text-sm"
                      inputMode="numeric"
                      value={f.trials}
                      onChange={(e) => update(f.key, { trials: Math.min(100, Number(e.target.value.replace(/\D/g, '')) || 0) })}
                    />
                  ) : (
                    <span className="text-ink-3">—</span>
                  )}
                </td>
                <td className="px-4 py-2.5 text-xs">
                  {f.access === 'free' && <span className="text-good">✓ Ai cũng dùng</span>}
                  {f.access === 'business' && (
                    <span className="text-accent">★ Cần Business{f.trials > 0 ? ` (Free thử ${f.trials} lượt/máy)` : ''}</span>
                  )}
                  {f.access === 'off' && <span className="text-bad">✕ Tạm tắt với mọi người</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center gap-3">
        <Button disabled={busy || !dirty} onClick={save}>
          Lưu thay đổi
        </Button>
        {dirty && !busy && <span className="text-sm text-warn">Có thay đổi chưa lưu</span>}
        {msg && <span className={`text-sm ${msg.ok ? 'text-good' : 'text-bad'}`}>{msg.ok ? `✓ ${msg.text}` : msg.text}</span>}
      </div>
      <p className="text-xs text-ink-3">
        Sao lưu đám mây được chặn cả ở máy chủ; các tính năng khác chạy trên điện thoại nên khoá trong app. App bản cũ (cài trước khi có trang này) chưa khoá được 2 ô mới
        (xuất JPG/TXT, chụp để dịch) — ép cập nhật sẽ đưa mọi máy lên bản mới.
      </p>
    </div>
  );
}
