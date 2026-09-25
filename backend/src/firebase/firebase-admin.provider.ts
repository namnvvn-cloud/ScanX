import * as admin from 'firebase-admin';

let app: admin.app.App | null = null;

/**
 * Khởi tạo Firebase Admin SDK (lazy, 1 lần) từ biến môi trường FIREBASE_SERVICE_ACCOUNT_JSON.
 * Dùng để verify Firebase ID token do app Android gửi lên (sau khi user đăng nhập Google/Email
 * qua Firebase Auth trên client).
 */
export function getFirebaseAdmin(): admin.app.App {
  if (app) return app;
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!raw) {
    throw new Error('Thiếu biến môi trường FIREBASE_SERVICE_ACCOUNT_JSON');
  }
  const serviceAccount = JSON.parse(raw);
  app = admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
  });
  return app;
}
