import { UserRow } from '../database/entities';

/**
 * Danh sách tính năng Business — NGUỒN DUY NHẤT cho app Android/iOS và web (app đọc qua GET /users/me,
 * không hard-code). Đổi gói/lượt dùng thử chỉ cần sửa file này + deploy backend, không phải cập nhật app.
 *
 * Nguyên tắc: MIỄN PHÍ mọi thứ chạy trên máy & là nhu cầu cơ bản (quét, lọc, cắt, OCR, PDF 4 chế độ,
 * xuất ảnh/TXT, chụp để dịch, dịch trực tiếp). BUSINESS = tính năng tạo giá trị công việc cao hoặc tốn
 * tài nguyên máy chủ (lưu trữ đám mây).
 */
export const FEATURE_KEYS = ['office_export', 'doc_translate', 'ai_handwriting', 'cloud_backup'] as const;
export type FeatureKey = (typeof FEATURE_KEYS)[number];

export const FEATURE_LABELS: Record<FeatureKey, string> = {
  office_export: 'Chuyển sang Word / Excel / PowerPoint giữ bố cục',
  doc_translate: 'Dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)',
  ai_handwriting: 'AI Cloud đọc chữ viết tay, bản chụp khó',
  cloud_backup: 'Sao lưu tài liệu lên đám mây',
};

/** Số lượt dùng thử miễn phí trên mỗi thiết bị (đếm ở app). 0 = chỉ Business. */
export const FREE_TRIALS: Record<FeatureKey, number> = {
  office_export: 3,
  doc_translate: 3,
  ai_handwriting: 0,
  cloud_backup: 0,
};

export function businessActive(user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined, now = new Date()): boolean {
  if (!user || !user.is_business) return false;
  return !user.business_expires_at || new Date(user.business_expires_at).getTime() > now.getTime();
}

export interface Entitlements {
  plan: 'business' | 'free';
  businessActive: boolean;
  /** ISO; null = không thời hạn (khi businessActive) hoặc chưa từng mua. */
  businessExpiresAt: string | null;
  features: Record<FeatureKey, boolean>;
  featureLabels: Record<FeatureKey, string>;
  freeTrials: Record<FeatureKey, number>;
  serverTime: string;
}

export function entitlementsFor(user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined, now = new Date()): Entitlements {
  const active = businessActive(user, now);
  const features = Object.fromEntries(FEATURE_KEYS.map((k) => [k, active])) as Record<FeatureKey, boolean>;
  return {
    plan: active ? 'business' : 'free',
    businessActive: active,
    businessExpiresAt: user?.business_expires_at ? new Date(user.business_expires_at).toISOString() : null,
    features,
    featureLabels: FEATURE_LABELS,
    freeTrials: FREE_TRIALS,
    serverTime: now.toISOString(),
  };
}

/** Hồ sơ user kèm trạng thái Business đã tính sẵn (app không phải tự so ngày hết hạn). */
export function withEntitlements<T extends Pick<UserRow, 'is_business' | 'business_expires_at'>>(user: T) {
  return { ...user, business_active: businessActive(user), entitlements: entitlementsFor(user) };
}
