'use client';

import Link from 'next/link';
import { useState } from 'react';
import { LoginPanel } from '@/components/LoginPanel';
import { Button, Card, ErrorBox, SiteHeader, Spinner } from '@/components/ui';
import { api, vnd } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useApi } from '@/lib/useApi';

interface Plan {
  id: string;
  name: string;
  description: string | null;
  price_vnd: number;
  duration_days: number;
}
type Provider = 'vnpay' | 'momo';
interface CatalogFeature {
  key: string;
  label: string;
  access: 'free' | 'business' | 'off';
  trials: number;
}
interface FeatureCatalog {
  core: { key: string; label: string }[];
  features: CatalogFeature[];
}

/** Bảng so sánh Miễn phí / Business — theo đúng chính sách admin tích chọn (Quản trị → Tính năng). */
function FeatureTable({ catalog }: { catalog: FeatureCatalog }) {
  const rows: [string, string, string][] = [
    ...catalog.core.map((c): [string, string, string] => [c.label, '✓', '✓']),
    ...catalog.features
      .filter((f) => f.access !== 'off')
      .map((f): [string, string, string] => [
        f.label,
        f.access === 'free' ? '✓' : f.trials > 0 ? `Dùng thử ${f.trials} lượt` : '—',
        '✓',
      ]),
  ];
  return (
    <div className="mt-8 overflow-x-auto rounded-xl border border-line bg-surface">
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-ink-3">
          <tr>
            <th className="px-4 py-3 font-medium">Tính năng</th>
            <th className="px-4 py-3 text-center font-medium">Miễn phí</th>
            <th className="px-4 py-3 text-center font-medium text-accent">Business</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, free, business]) => (
            <tr key={label} className="border-t border-line">
              <td className="px-4 py-2.5">{label}</td>
              <td className={`px-4 py-2.5 text-center ${free === '✓' ? 'text-good' : 'text-ink-3'}`}>{free}</td>
              <td className="px-4 py-2.5 text-center text-good">{business}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const PROVIDER_LABEL: Record<Provider, string> = { vnpay: 'VNPay (ATM / QR / thẻ quốc tế)', momo: 'Ví MoMo' };

export default function PricingPage() {
  const { user, loading: authLoading } = useAuth();
  const { data, error, loading } = useApi<{ plans: Plan[]; providers: Record<Provider, boolean>; featureCatalog?: FeatureCatalog }>('/plans');
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState<Provider | null>(null);
  const [payError, setPayError] = useState<string | null>(null);

  const providers = (['vnpay', 'momo'] as Provider[]).filter((p) => data?.providers?.[p]);
  const plan = data?.plans.find((p) => p.id === selected) ?? null;

  async function pay(provider: Provider) {
    if (!plan) return;
    setBusy(provider);
    setPayError(null);
    try {
      const returnUrl = `${window.location.origin}/billing/result?provider=${provider}`;
      const res = await api<{ orderCode: string; payUrl: string }>('/payments/checkout', {
        method: 'POST',
        body: JSON.stringify({ planId: plan.id, provider, returnUrl }),
      });
      window.location.href = res.payUrl;
    } catch (e: any) {
      setPayError(e.message);
      setBusy(null);
    }
  }

  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-5xl px-4 py-10">
        <h1 className="text-2xl font-bold">Gói Business</h1>
        <p className="mt-1 text-sm text-ink-2">
          Thanh toán một lần, không tự động gia hạn. Mua thêm khi còn hạn → thời gian được cộng dồn.
        </p>

        {loading && <Spinner label="Đang tải bảng giá (máy chủ có thể mất ~1 phút để khởi động)…" />}
        {error && <div className="mt-6"><ErrorBox message={error.message} /></div>}

        {data && (
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            {data.plans.map((p) => {
              const perMonth = Math.round(p.price_vnd / Math.max(1, p.duration_days / 30));
              const active = p.id === selected;
              return (
                <button
                  key={p.id}
                  onClick={() => setSelected(p.id)}
                  aria-pressed={active}
                  className={`flex flex-col rounded-xl border bg-surface p-5 text-left transition hover:bg-surface-2 ${
                    active ? 'border-accent ring-2 ring-accent/30' : 'border-line'
                  }`}
                >
                  <div className="flex items-baseline justify-between gap-3">
                    <h2 className="font-semibold">{p.name}</h2>
                    <span className="text-xs text-ink-3">{p.duration_days} ngày</span>
                  </div>
                  <p className="tabular mt-3 text-2xl font-bold">{vnd(p.price_vnd)}</p>
                  {p.duration_days > 31 && <p className="tabular text-xs text-ink-2">≈ {vnd(perMonth)} / tháng</p>}
                  {p.description && <p className="mt-3 text-sm text-ink-2">{p.description}</p>}
                </button>
              );
            })}
          </div>
        )}

        {plan && (
          <Card className="mt-6">
            <h2 className="font-semibold">
              Thanh toán: {plan.name} — <span className="tabular">{vnd(plan.price_vnd)}</span>
            </h2>
            {authLoading ? (
              <Spinner />
            ) : !user ? (
              <div className="mt-4">
                <LoginPanel title="Đăng nhập để mua" note="Gói sẽ kích hoạt cho tài khoản bạn dùng trong app ScanX." />
              </div>
            ) : (
              <div className="mt-4 space-y-3">
                <p className="text-sm text-ink-2">
                  Kích hoạt cho: <strong className="text-ink">{user.email}</strong>
                </p>
                {providers.length === 0 && <ErrorBox message="Cổng thanh toán chưa được cấu hình. Vui lòng liên hệ quản trị viên." />}
                <div className="flex flex-wrap gap-3">
                  {providers.map((p) => (
                    <Button key={p} disabled={busy !== null} onClick={() => pay(p)}>
                      {busy === p ? 'Đang chuyển…' : PROVIDER_LABEL[p]}
                    </Button>
                  ))}
                </div>
                {payError && <ErrorBox message={payError} />}
              </div>
            )}
          </Card>
        )}

        {data?.featureCatalog && <FeatureTable catalog={data.featureCatalog} />}

        <p className="mt-8 text-xs text-ink-3">
          Đã thanh toán? Xem trạng thái tại <Link href="/account" className="text-accent underline">Tài khoản</Link>.
        </p>
      </main>
    </>
  );
}
