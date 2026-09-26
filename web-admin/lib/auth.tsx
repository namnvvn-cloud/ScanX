'use client';

import {
  createUserWithEmailAndPassword,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut as fbSignOut,
  type User,
} from 'firebase/auth';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { firebaseAuth, firebaseConfigured } from './firebase';

interface AuthState {
  user: User | null;
  loading: boolean;
  configured: boolean;
  signInGoogle: () => Promise<void>;
  signInEmail: (email: string, password: string) => Promise<void>;
  registerEmail: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!firebaseConfigured) {
      setLoading(false);
      return;
    }
    return onAuthStateChanged(firebaseAuth(), (u) => {
      setUser(u);
      setLoading(false);
    });
  }, []);

  const value: AuthState = {
    user,
    loading,
    configured: firebaseConfigured,
    signInGoogle: async () => {
      await signInWithPopup(firebaseAuth(), new GoogleAuthProvider());
    },
    signInEmail: async (email, password) => {
      await signInWithEmailAndPassword(firebaseAuth(), email, password);
    },
    registerEmail: async (email, password) => {
      await createUserWithEmailAndPassword(firebaseAuth(), email, password);
    },
    signOut: async () => {
      await fbSignOut(firebaseAuth());
    },
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth phải nằm trong AuthProvider');
  return v;
}

/** Thông báo lỗi Firebase Auth dễ hiểu bằng tiếng Việt. */
export function authErrorMessage(err: any): string {
  const code: string = err?.code || '';
  const map: Record<string, string> = {
    'auth/invalid-credential': 'Email hoặc mật khẩu không đúng',
    'auth/wrong-password': 'Mật khẩu không đúng',
    'auth/user-not-found': 'Chưa có tài khoản với email này — bấm "Đăng ký"',
    'auth/email-already-in-use': 'Email đã được đăng ký — bấm "Đăng nhập"',
    'auth/weak-password': 'Mật khẩu cần tối thiểu 6 ký tự',
    'auth/invalid-email': 'Email không hợp lệ',
    'auth/popup-closed-by-user': 'Đã đóng cửa sổ đăng nhập Google',
    'auth/unauthorized-domain': 'Tên miền web chưa được thêm vào Firebase (Authentication → Settings → Authorized domains)',
    'auth/operation-not-allowed': 'Phương thức đăng nhập này chưa được bật trong Firebase',
    'auth/too-many-requests': 'Thử quá nhiều lần, vui lòng đợi ít phút',
  };
  return map[code] || err?.message || 'Đăng nhập thất bại';
}
