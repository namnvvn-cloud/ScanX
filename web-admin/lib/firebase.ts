'use client';

import { getApp, getApps, initializeApp, type FirebaseApp } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';

/** Firebase Web App (project scanx-app) — cấu hình qua NEXT_PUBLIC_FIREBASE_* (không phải bí mật). */
const config = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN || 'scanx-app.firebaseapp.com',
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID || 'scanx-app',
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

export const firebaseConfigured = Boolean(config.apiKey && config.appId);

let app: FirebaseApp | null = null;

export function firebaseAuth(): Auth {
  if (!app) app = getApps().length ? getApp() : initializeApp(config);
  return getAuth(app);
}
