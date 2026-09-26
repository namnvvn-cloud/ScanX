'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { Card, ErrorBox, SiteHeader, Spinner } from '@/components/ui';
import { api } from '@/lib/api';

interface Result {
  ok: boolean;
  orderCode: string | null;
  status: string;
  message: string;
}

/** VNPay/MoMo chuyển người dùng về đây kèm query; gửi lại backend để kiểm chữ ký & cập nhật đơn (IPN vẫn là nguồn chính). */
function ResultView() {
  const params = useSearchParams();
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const provider = params.get('provider') === 'momo' ? 'momo' : 'vnpay';
    const query = new URLSearchParams(params);
    query.delete('provider');
    api<Result>(`/payments/${provider}/return?${query.toString()}`, { auth: false })
      .then(setResult)
      .catch((e) => setError(e.message));
  }, [params]);

  if (error) return <ErrorBox message={error} />;
  if (!result) return <Spinner label="Đang xác nhận thanh toán…" />;

  const pending = !result.ok && result.status === 'pending';
  return (
    <Card className="mx-auto max-w-md text-center">
      <div
        aria-hidden
        className={`mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-surface-2 text-2xl ${
          result.ok ? 'text-good' : pending ? 'text-warn' : 'text-bad'
        }`}
      >
        {result.ok ? '✓' : pending ? '…' : '✕'}
      </div>
      <h1 className="mt-3 text-xl font-semibold">
        {result.ok ? 'Thanh toán thành công' : pending ? 'Đang chờ xác nhận' : 'Thanh toán không thành công'}
      </h1>
      <p className="mt-2 text-sm text-ink-2">{result.message}</p>
      {result.orderCode && <p className="mt-1 font-mono text-xs text-ink-3">Mã đơn: {result.orderCode}</p>}
      <div className="mt-5 flex justify-center gap-3">
        <Link href="/account" className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-accent-ink hover:opacity-90">
          Xem tài khoản
        </Link>
        {!result.ok && (
          <Link href="/pricing" className="rounded-lg border border-line px-4 py-2 text-sm font-medium hover:bg-surface-2">
            Thử lại
          </Link>
        )}
      </div>
    </Card>
  );
}

export default function BillingResultPage() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-5xl px-4 py-12">
        <Suspense fallback={<Spinner />}>
          <ResultView />
        </Suspense>
      </main>
    </>
  );
}
