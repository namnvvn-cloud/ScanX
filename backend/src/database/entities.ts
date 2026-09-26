export interface UserRow {
  id: string;
  firebase_uid: string;
  email: string | null;
  display_name: string | null;
  is_business: boolean;
  business_expires_at: Date | null;
  last_login_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

export interface DocumentRow {
  id: string;
  user_id: string;
  title: string;
  page_count: number;
  file_key: string;
  file_size: number | null;
  mime_type: string | null;
  created_at: Date;
  updated_at: Date;
}

export interface PlanRow {
  id: string;
  name: string;
  description: string | null;
  price_vnd: number;
  duration_days: number;
  active: boolean;
  sort_order: number;
  updated_at: Date;
}

export type OrderStatus = 'pending' | 'paid' | 'failed' | 'cancelled';
export type PaymentProvider = 'vnpay' | 'momo' | 'manual';

export interface OrderRow {
  id: string;
  code: string;
  user_id: string;
  plan_id: string;
  amount_vnd: number;
  duration_days: number;
  provider: PaymentProvider;
  status: OrderStatus;
  provider_txn_id: string | null;
  provider_response: any;
  paid_at: Date | null;
  created_at: Date;
  updated_at: Date;
}
