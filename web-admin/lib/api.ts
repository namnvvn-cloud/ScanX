'use client';

import { firebaseAuth } from './firebase';

export const API_URL = (process.env.NEXT_PUBLIC_API_URL || 'https://scanx-450n.onrender.com').replace(/\/$/, '');

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Gọi backend; tự gắn Firebase ID token nếu đã đăng nhập. Render free tier có thể "ngủ" ~50 s lần đầu. */
export async function api<T = any>(path: string, init: RequestInit & { auth?: boolean } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('content-type')) headers.set('content-type', 'application/json');
  if (init.auth !== false) {
    const user = firebaseAuth().currentUser;
    if (user) headers.set('authorization', `Bearer ${await user.getIdToken()}`);
  }
  const res = await fetch(`${API_URL}${path}`, { ...init, headers });
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    const msg = (data && (Array.isArray(data.message) ? data.message.join(', ') : data.message)) || `Lỗi ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

function safeJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text.slice(0, 200) };
  }
}

export const vnd = (n: number | string | null | undefined) =>
  new Intl.NumberFormat('vi-VN').format(Number(n || 0)) + ' ₫';

export const dateTime = (d: string | null | undefined) =>
  d ? new Date(d).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) : '—';

export const dateOnly = (d: string | null | undefined) =>
  d ? new Date(d).toLocaleDateString('vi-VN') : '—';

export function bytes(n: number | string | null | undefined): string {
  let v = Number(n || 0);
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

export function businessActive(u: { is_business: boolean; business_expires_at: string | null }): boolean {
  return u.is_business && (!u.business_expires_at || new Date(u.business_expires_at) > new Date());
}
