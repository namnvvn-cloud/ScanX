export interface UserRow {
  id: string;
  firebase_uid: string;
  email: string | null;
  display_name: string | null;
  is_business: boolean;
  business_expires_at: Date | null;
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
