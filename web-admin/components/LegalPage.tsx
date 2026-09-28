'use client';

import type { ReactNode } from 'react';
import { SiteHeader } from '@/components/ui';
import { useApi } from '@/lib/useApi';

export interface PublicProductInfo {
  appName: string;
  publisher: string;
  email: string;
  phone: string;
  address: string;
  website: string;
}

/** Khung chung cho Chính sách quyền riêng tư / Điều khoản — thông tin nhà phát hành lấy từ Web Admin → Ứng dụng. */
export function LegalPage({
  title,
  updated,
  children,
}: {
  title: string;
  updated: string;
  children: (info: PublicProductInfo) => ReactNode;
}) {
  const { data } = useApi<{ productInfo: PublicProductInfo }>('/app/config?platform=web&versionCode=0');
  const info: PublicProductInfo = data?.productInfo ?? { appName: 'ScanX', publisher: '', email: '', phone: '', address: '', website: '' };
  const contacts = [
    info.publisher && `Nhà phát hành: ${info.publisher}`,
    info.email && `Email: ${info.email}`,
    info.phone && `Điện thoại: ${info.phone}`,
    info.address && `Địa chỉ: ${info.address}`,
  ].filter(Boolean) as string[];

  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-3xl px-4 py-10">
        <h1 className="text-2xl font-bold">{title}</h1>
        <p className="mt-1 text-sm text-ink-3">Cập nhật: {updated}</p>
        <article className="legal mt-6 space-y-5 text-[15px] leading-relaxed text-ink-2">{children(info)}</article>
        <section className="mt-8 rounded-xl border border-line bg-surface p-5 text-sm">
          <h2 className="font-semibold text-ink">Liên hệ</h2>
          {contacts.length ? (
            <ul className="mt-2 space-y-1 text-ink-2">
              {contacts.map((c) => (
                <li key={c}>{c}</li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 text-ink-2">Vui lòng liên hệ qua mục Cài đặt → Thông tin sản phẩm trong ứng dụng {info.appName}.</p>
          )}
        </section>
      </main>
    </>
  );
}

export function H2({ children }: { children: ReactNode }) {
  return <h2 className="pt-2 text-lg font-semibold text-ink">{children}</h2>;
}
