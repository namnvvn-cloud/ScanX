/**
 * Chuẩn hoá DATABASE_URL trước khi đưa cho pg:
 *  - bỏ khoảng trắng / xuống dòng / dấu nháy bao ngoài (hay dính khi copy từ file .env);
 *  - mật khẩu chứa ký tự đặc biệt (@ # / ? : % …) chưa percent-encode → tự encode.
 * Mật khẩu = đoạn giữa dấu ":" đầu tiên sau "user" và dấu "@" CUỐI CÙNG trước host.
 */
export function normalizeDatabaseUrl(raw: string | undefined): string | undefined {
  if (!raw) return raw;
  let s = raw.trim();
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) s = s.slice(1, -1).trim();
  const m = /^(postgres(?:ql)?:\/\/)([^:@/]+)(?::(.*))?@([^@/?#]+)(\/[^?#]*)?(\?.*)?$/s.exec(s);
  if (!m) return s;
  const [, scheme, user, rawPassword, host, path = '/postgres', rawQuery = ''] = m;
  let password = rawPassword;
  // Mẫu Supabase ghi "[YOUR-PASSWORD]" — hay bị giữ nguyên dấu ngoặc vuông khi thay mật khẩu.
  if (password && password.length > 2 && password.startsWith('[') && password.endsWith(']')) password = password.slice(1, -1);
  // sslmode trong URL sẽ ghi đè cấu hình ssl của Pool (pg coi require = verify-full → lỗi cert pooler) → bỏ.
  const params = new URLSearchParams(rawQuery.replace(/^\?/, ''));
  params.delete('sslmode');
  const query = params.toString() ? `?${params}` : '';
  const enc = (v: string) => encodeURIComponent(safeDecode(v));
  return `${scheme}${enc(user)}${password !== undefined ? ':' + enc(password) : ''}@${host}${path}${query}`;
}

function safeDecode(v: string): string {
  try {
    return decodeURIComponent(v);
  } catch {
    return v; // có "%" lẻ (không phải mã hoá) → coi là ký tự thường
  }
}

/** Mô tả an toàn để log (không lộ mật khẩu). */
export function describeDatabaseUrl(url: string | undefined): string {
  if (!url) return '(chưa đặt DATABASE_URL)';
  try {
    const u = new URL(url);
    return `${u.protocol}//${decodeURIComponent(u.username)}:***@${u.host}${u.pathname}`;
  } catch {
    return `(DATABASE_URL không đúng định dạng, dài ${url.length} ký tự, bắt đầu bằng "${url.slice(0, 13)}")`;
  }
}
