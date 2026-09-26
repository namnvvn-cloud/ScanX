'use client';

import { useState } from 'react';
import { authErrorMessage, useAuth } from '@/lib/auth';
import { Button, Card, ErrorBox, Field, inputClass } from './ui';

/** Đăng nhập Google / Email-mật khẩu (cùng tài khoản Firebase với app Android/iOS). */
export function LoginPanel({ title = 'Đăng nhập ScanX', note }: { title?: string; note?: string }) {
  const { signInGoogle, signInEmail, registerEmail, configured } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (err) {
      setError(authErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (!configured) {
    return <ErrorBox message="Web chưa cấu hình Firebase (NEXT_PUBLIC_FIREBASE_API_KEY / NEXT_PUBLIC_FIREBASE_APP_ID)." />;
  }

  return (
    <Card className="mx-auto w-full max-w-md">
      <h1 className="text-xl font-semibold">{title}</h1>
      {note && <p className="mt-1 text-sm text-ink-2">{note}</p>}
      <div className="mt-5 space-y-4">
        <Button variant="secondary" className="w-full" disabled={busy} onClick={() => run(signInGoogle)}>
          <span aria-hidden className="font-bold">G</span> Đăng nhập bằng Google
        </Button>
        <div className="flex items-center gap-3 text-xs text-ink-3">
          <span className="h-px flex-1 bg-line" /> hoặc email <span className="h-px flex-1 bg-line" />
        </div>
        <Field label="Email">
          <input className={inputClass} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Mật khẩu" hint="Tối thiểu 6 ký tự">
          <input
            className={inputClass}
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        {error && <ErrorBox message={error} />}
        <div className="grid grid-cols-2 gap-3">
          <Button disabled={busy || !email || !password} onClick={() => run(() => signInEmail(email, password))}>
            Đăng nhập
          </Button>
          <Button variant="secondary" disabled={busy || !email || !password} onClick={() => run(() => registerEmail(email, password))}>
            Đăng ký
          </Button>
        </div>
      </div>
    </Card>
  );
}
