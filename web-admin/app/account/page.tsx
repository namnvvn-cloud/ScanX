'use client';

import Link from 'next/link';
import { LoginPanel } from '@/components/LoginPanel';
import { Button, Card, ErrorBox, OrderStatus, SiteHeader, Spinner } from '@/components/ui';
import { businessActive, dateOnly, dateTime, vnd } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useApi } from '@/lib/useApi';

interface Me {
  user: { email: string | null; display_name: string | null; is_business: boolean; business_expires_at: string | null };
  orders: { id: string; code: string; plan_id: string; amount_vnd: number; provider: string; status: string; created_at: string; paid_at: string | null }[];
}

export default function AccountPage() {
  const { user, loading, signOut } = useAuth();
  const { data, error, loading: loadingMe } = useApi<Me>(user ? '/billing/me' : null);

  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-5xl px-4 py-10">
        {loading ? (
          <Spinner />
        ) : !user ? (
          <LoginPanel note="Đăng nhập để xem gói Business và lịch sử thanh toán." />
        ) : (
          <div className="space-y-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h1 className="text-2xl font-bold">Tài khoản</h1>
                <p className="text-sm text-ink-2">{user.email}</p>
              </div>
              <Button variant="secondary" onClick={signOut}>
                Đăng xuất
              </Button>
            </div>

            {loadingMe && <Spinner />}
            {error && <ErrorBox message={error.message} />}

            {data && (
              <>
                <Card>
                  <h2 className="text-sm font-medium text-ink-2">Gói hiện tại</h2>
                  {businessActive(data.user) ? (
                    <p className="mt-2 text-lg font-semibold">
                      Business <span className="text-good">✓ đang hoạt động</span>
                      <span className="block text-sm font-normal text-ink-2">
                        {data.user.business_expires_at ? `Hết hạn: ${dateOnly(data.user.business_expires_at)}` : 'Không thời hạn'}
                      </span>
                    </p>
                  ) : (
                    <p className="mt-2 text-lg font-semibold">
                      Miễn phí
                      {data.user.business_expires_at && (
                        <span className="block text-sm font-normal text-ink-2">Business đã hết hạn {dateOnly(data.user.business_expires_at)}</span>
                      )}
                    </p>
                  )}
                  <Link href="/pricing" className="mt-4 inline-block rounded-lg bg-accent px-4 py-2 text-sm font-medium text-accent-ink hover:opacity-90">
                    {businessActive(data.user) ? 'Gia hạn' : 'Nâng cấp Business'}
                  </Link>
                </Card>

                <Card>
                  <h2 className="font-semibold">Lịch sử thanh toán</h2>
                  {data.orders.length === 0 ? (
                    <p className="mt-2 text-sm text-ink-2">Chưa có đơn hàng.</p>
                  ) : (
                    <div className="mt-3 overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead className="text-left text-xs text-ink-3">
                          <tr>
                            <th className="py-2 pr-4 font-medium">Mã đơn</th>
                            <th className="py-2 pr-4 font-medium">Ngày tạo</th>
                            <th className="py-2 pr-4 font-medium">Cổng</th>
                            <th className="py-2 pr-4 text-right font-medium">Số tiền</th>
                            <th className="py-2 font-medium">Trạng thái</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.orders.map((o) => (
                            <tr key={o.id} className="border-t border-line">
                              <td className="py-2 pr-4 font-mono text-xs">{o.code}</td>
                              <td className="py-2 pr-4">{dateTime(o.created_at)}</td>
                              <td className="py-2 pr-4 uppercase">{o.provider}</td>
                              <td className="tabular py-2 pr-4 text-right">{vnd(o.amount_vnd)}</td>
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
              </>
            )}
          </div>
        )}
      </main>
    </>
  );
}
