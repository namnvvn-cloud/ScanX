'use client';

import { useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { LoginPanel } from '@/components/LoginPanel';
import { SiteHeader, Spinner } from '@/components/ui';
import { useAuth } from '@/lib/auth';

export default function LoginPage() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (user) router.replace('/account');
  }, [user, router]);

  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-5xl px-4 py-12">
        {loading || user ? <Spinner /> : <LoginPanel note="Dùng cùng tài khoản với app ScanX trên điện thoại." />}
      </main>
    </>
  );
}
