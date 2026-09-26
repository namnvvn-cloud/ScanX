import Link from 'next/link';
import { SiteHeader } from '@/components/ui';

const FEATURES = [
  ['Quét & làm nét', 'Tự nhận mép giấy, cắt phẳng, lọc màu/đen trắng, xoá bóng.'],
  ['OCR tiếng Việt', 'Nhận dạng chữ, tìm kiếm trong tài liệu, xuất PDF có lớp chữ.'],
  ['Chuyển Word / Excel', 'Giữ bố cục bảng biểu, xuất .docx / .xlsx ngay trên máy.'],
  ['Dịch tài liệu', 'Dịch ảnh chụp và PDF, xem song song bản gốc – bản dịch.'],
];

export default function Home() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-5xl px-4 py-14">
        <section className="max-w-2xl">
          <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">Quét tài liệu thông minh trên Android & iOS</h1>
          <p className="mt-4 text-ink-2">
            ScanX miễn phí cho nhu cầu cơ bản. Gói <strong className="text-ink">Business</strong> mở khoá các tính năng nâng cao — mua trên web, dùng ngay trên mọi thiết bị đăng nhập cùng tài khoản.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <Link href="/pricing" className="rounded-lg bg-accent px-5 py-2.5 text-sm font-medium text-accent-ink hover:opacity-90">
              Xem bảng giá
            </Link>
            <Link href="/account" className="rounded-lg border border-line bg-surface px-5 py-2.5 text-sm font-medium hover:bg-surface-2">
              Tài khoản của tôi
            </Link>
          </div>
        </section>
        <section className="mt-14 grid gap-4 sm:grid-cols-2">
          {FEATURES.map(([t, d]) => (
            <div key={t} className="rounded-xl border border-line bg-surface p-5">
              <h2 className="font-semibold">{t}</h2>
              <p className="mt-1 text-sm text-ink-2">{d}</p>
            </div>
          ))}
        </section>
      </main>
      <footer className="border-t border-line py-6 text-center text-xs text-ink-3">© {new Date().getFullYear()} ScanX</footer>
    </>
  );
}
