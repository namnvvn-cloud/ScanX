import { Injectable } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';
import { UserRow } from '../database/entities';

/**
 * Chính sách tính năng Miễn phí / Business — admin tích chọn trên Web Admin → "Tính năng" (lưu
 * app_config.feature_policy). App Android/iOS + trang bảng giá đọc qua API, KHÔNG hard-code:
 * đổi quyền 1 tính năng không cần phát hành lại app.
 *
 * - Tính năng LÕI (quét, OCR/PDF) luôn miễn phí — không cho tắt, vì là lý do người dùng cài app.
 * - Tính năng còn lại: access = 'free' (ai cũng dùng) | 'business' (cần gói; Free được `trials` lượt thử
 *   mỗi máy) | 'off' (tạm tắt với mọi người).
 */
export const FEATURE_KEYS = [
  'export_image_text',
  'camera_translate',
  'text_scan',
  'book_scan',
  'qr_scan',
  'signatures',
  'email_templates',
  'cloud_folders',
  'expense_report',
  'workflows',
  'auto_upload',
  'office_export',
  'doc_translate',
  'ai_handwriting',
  'cloud_backup',
] as const;
export type FeatureKey = (typeof FEATURE_KEYS)[number];
export type FeatureAccess = 'free' | 'business' | 'off';

export interface FeatureRule {
  access: FeatureAccess;
  /** Lượt dùng thử / máy cho người dùng Free khi access = 'business'. */
  trials: number;
}

export const CORE_FEATURES: { key: string; label: string }[] = [
  { key: 'core_scan', label: 'Quét tài liệu: tự nhận mép giấy, lọc màu / đen trắng, cắt xoay, làm sạch' },
  { key: 'core_ocr_pdf', label: 'OCR tiếng Việt, PDF có lớp chữ tìm kiếm được (4 chế độ)' },
];

export const FEATURE_LABELS: Record<FeatureKey, string> = {
  export_image_text: 'Xuất ảnh JPG, văn bản TXT',
  camera_translate: 'Chụp để dịch, dịch trực tiếp khi soi camera',
  text_scan: 'Quét lấy văn bản (chụp → chữ, sao chép / chia sẻ)',
  book_scan: 'Quét sách: tự tách 2 trang mở',
  qr_scan: 'Quét mã QR / mã vạch',
  signatures: 'Chữ ký: vẽ và chèn chữ ký vào tài liệu',
  email_templates: 'Gửi email theo mẫu (tiêu đề, nội dung, người nhận)',
  cloud_folders: 'Lưu vào thư mục đám mây (Google Drive, OneDrive, Dropbox, iCloud…)',
  expense_report: 'Báo cáo chi phí từ hoá đơn, biên lai (xuất CSV/Excel)',
  workflows: 'Quy trình tự động sau khi quét',
  auto_upload: 'Tự động tải tài liệu mới lên đám mây',
  office_export: 'Chuyển sang Word / Excel / PowerPoint giữ bố cục',
  doc_translate: 'Dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)',
  ai_handwriting: 'AI Cloud đọc chữ viết tay, bản chụp khó',
  cloud_backup: 'Sao lưu tài liệu lên đám mây',
};

export const DEFAULT_POLICY: Record<FeatureKey, FeatureRule> = {
  export_image_text: { access: 'free', trials: 0 },
  camera_translate: { access: 'free', trials: 0 },
  text_scan: { access: 'free', trials: 0 },
  book_scan: { access: 'free', trials: 0 },
  qr_scan: { access: 'free', trials: 0 },
  signatures: { access: 'free', trials: 0 },
  email_templates: { access: 'free', trials: 0 },
  cloud_folders: { access: 'free', trials: 0 },
  expense_report: { access: 'business', trials: 3 },
  workflows: { access: 'business', trials: 3 },
  auto_upload: { access: 'business', trials: 0 },
  office_export: { access: 'business', trials: 3 },
  doc_translate: { access: 'business', trials: 3 },
  ai_handwriting: { access: 'business', trials: 0 },
  cloud_backup: { access: 'business', trials: 0 },
};

export type FeaturePolicy = Record<FeatureKey, FeatureRule>;

export function normalizePolicy(raw: any): FeaturePolicy {
  const out = {} as FeaturePolicy;
  for (const k of FEATURE_KEYS) {
    const r = raw?.[k] ?? {};
    const access: FeatureAccess = ['free', 'business', 'off'].includes(r.access) ? r.access : DEFAULT_POLICY[k].access;
    const trials = Number.isFinite(Number(r.trials)) ? Math.max(0, Math.min(100, Math.floor(Number(r.trials)))) : DEFAULT_POLICY[k].trials;
    out[k] = { access, trials: access === 'business' ? trials : 0 };
  }
  return out;
}

export function businessActive(user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined, now = new Date()): boolean {
  if (!user || !user.is_business) return false;
  return !user.business_expires_at || new Date(user.business_expires_at).getTime() > now.getTime();
}

export function featureAllowed(policy: FeaturePolicy, key: FeatureKey, active: boolean): boolean {
  const a = policy[key].access;
  return a === 'free' || (a === 'business' && active);
}

export interface Entitlements {
  plan: 'business' | 'free';
  businessActive: boolean;
  businessExpiresAt: string | null;
  /** Được dùng NGAY với tài khoản này (đã tính gói + chính sách). */
  features: Record<FeatureKey, boolean>;
  /** Chính sách gốc — app dùng để tự tính lại khi gói hết hạn lúc offline. */
  featureAccess: Record<FeatureKey, FeatureAccess>;
  featureLabels: Record<FeatureKey, string>;
  freeTrials: Record<FeatureKey, number>;
  coreFeatures: { key: string; label: string }[];
  serverTime: string;
}

export function entitlementsFor(
  user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined,
  policy: FeaturePolicy = DEFAULT_POLICY,
  now = new Date(),
): Entitlements {
  const active = businessActive(user, now);
  const map = <T>(fn: (k: FeatureKey) => T) => Object.fromEntries(FEATURE_KEYS.map((k) => [k, fn(k)])) as Record<FeatureKey, T>;
  return {
    plan: active ? 'business' : 'free',
    businessActive: active,
    businessExpiresAt: user?.business_expires_at ? new Date(user.business_expires_at).toISOString() : null,
    features: map((k) => featureAllowed(policy, k, active)),
    featureAccess: map((k) => policy[k].access),
    featureLabels: FEATURE_LABELS,
    freeTrials: map((k) => policy[k].trials),
    coreFeatures: CORE_FEATURES,
    serverTime: now.toISOString(),
  };
}

/** Đọc/ghi chính sách trong DB (cache 30 s) và gắn entitlements vào hồ sơ user. */
@Injectable()
export class EntitlementsService {
  private cache: { at: number; policy: FeaturePolicy } | null = null;

  constructor(private readonly db: DatabaseService) {}

  async policy(): Promise<FeaturePolicy> {
    if (this.cache && Date.now() - this.cache.at < 30_000) return this.cache.policy;
    const { rows } = await this.db.query<{ value: any }>(`select value from app_config where key = 'feature_policy'`);
    const policy = normalizePolicy(rows[0]?.value);
    this.cache = { at: Date.now(), policy };
    return policy;
  }

  async setPolicy(raw: any): Promise<FeaturePolicy> {
    const policy = normalizePolicy(raw);
    await this.db.query(
      `insert into app_config (key, value, updated_at) values ('feature_policy', $1, now())
       on conflict (key) do update set value = excluded.value, updated_at = now()`,
      [JSON.stringify(policy)],
    );
    this.cache = { at: Date.now(), policy };
    return policy;
  }

  /** Danh mục cho web (bảng so sánh / checklist quản trị). */
  async catalog() {
    const policy = await this.policy();
    return {
      core: CORE_FEATURES,
      features: FEATURE_KEYS.map((key) => ({ key, label: FEATURE_LABELS[key], ...policy[key] })),
    };
  }

  async entitlementsFor(user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined) {
    return entitlementsFor(user, await this.policy());
  }

  async withEntitlements<T extends Pick<UserRow, 'is_business' | 'business_expires_at'>>(user: T) {
    return { ...user, business_active: businessActive(user), entitlements: await this.entitlementsFor(user) };
  }

  async allowed(user: Pick<UserRow, 'is_business' | 'business_expires_at'> | null | undefined, key: FeatureKey) {
    return featureAllowed(await this.policy(), key, businessActive(user));
  }
}
