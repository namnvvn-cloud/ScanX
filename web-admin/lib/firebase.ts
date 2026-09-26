'use client';

import { getApp, getApps, initializeApp, type FirebaseApp } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';

/**
 * Firebase Web App "ScanX Web" (project scanx-app). Cấu hình web Firebase KHÔNG phải bí mật (giống
 * google-services.json) — ghi sẵn làm mặc định, vẫn ghi đè được qua NEXT_PUBLIC_FIREBASE_*.
 */
const config = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY || 'AIzaSyDdwtTbAAG-j9MdaWY_ud3hY0ADSWuAjQI',
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN || 'scanx-app.firebaseapp.com',
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID || 'scanx-app',
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID || '1:455836379547:web:bc9f199507992a8e0cd93e',
};

export const firebaseConfigured = Boolean(config.apiKey && config.appId);

let app: FirebaseApp | null = null;

export function firebaseAuth(): Auth {
  if (!app) app = getApps().length ? getApp() : initializeApp(config);
  return getAuth(app);
}
