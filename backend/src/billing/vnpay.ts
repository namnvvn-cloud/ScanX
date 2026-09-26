import * as crypto from 'crypto';

/**
 * VNPay (cổng thanh toán nội địa) — API v2.1.0, ký HMAC-SHA512 theo tài liệu tích hợp VNPay:
 *  - Sắp xếp tham số theo tên (a→z), giá trị encodeURIComponent với khoảng trắng thành "+",
 *    nối "key=value&..." → ký bằng vnp_HashSecret → thêm vnp_SecureHash vào URL.
 *  - Kết quả (Return URL và IPN) được VNPay ký cùng cách → kiểm chữ ký trước khi tin dữ liệu.
 * Sandbox: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html (đăng ký TMN code tại sandbox.vnpayment.vn/devreg).
 */
export interface VnpayConfig {
  tmnCode: string;
  hashSecret: string;
  payUrl: string;
}

export function vnpayConfig(): VnpayConfig | null {
  const tmnCode = process.env.VNPAY_TMN_CODE;
  const hashSecret = process.env.VNPAY_HASH_SECRET;
  if (!tmnCode || !hashSecret) return null;
  return {
    tmnCode,
    hashSecret,
    payUrl: process.env.VNPAY_URL || 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html',
  };
}

function encode(value: string): string {
  return encodeURIComponent(value).replace(/%20/g, '+');
}

/** Chuỗi ký: tham số (bỏ rỗng) sắp xếp a→z, encode giá trị như VNPay. */
export function vnpaySignData(params: Record<string, string>): string {
  return Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== '')
    .sort()
    .map((k) => `${encode(k)}=${encode(String(params[k]))}`)
    .join('&');
}

export function vnpaySign(params: Record<string, string>, secret: string): string {
  return crypto.createHmac('sha512', secret).update(Buffer.from(vnpaySignData(params), 'utf-8')).digest('hex');
}

/** Giờ Việt Nam dạng yyyyMMddHHmmss (VNPay yêu cầu GMT+7). */
export function vnpayDate(d: Date): string {
  const t = new Date(d.getTime() + 7 * 3600 * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${t.getUTCFullYear()}${p(t.getUTCMonth() + 1)}${p(t.getUTCDate())}${p(t.getUTCHours())}${p(t.getUTCMinutes())}${p(t.getUTCSeconds())}`;
}

export function buildVnpayUrl(
  cfg: VnpayConfig,
  opts: { orderCode: string; amountVnd: number; orderInfo: string; returnUrl: string; ipAddr: string },
): string {
  const now = new Date();
  const params: Record<string, string> = {
    vnp_Version: '2.1.0',
    vnp_Command: 'pay',
    vnp_TmnCode: cfg.tmnCode,
    vnp_Locale: 'vn',
    vnp_CurrCode: 'VND',
    vnp_TxnRef: opts.orderCode,
    vnp_OrderInfo: opts.orderInfo,
    vnp_OrderType: 'other',
    vnp_Amount: String(opts.amountVnd * 100),
    vnp_ReturnUrl: opts.returnUrl,
    vnp_IpAddr: opts.ipAddr || '127.0.0.1',
    vnp_CreateDate: vnpayDate(now),
    vnp_ExpireDate: vnpayDate(new Date(now.getTime() + 15 * 60 * 1000)),
  };
  const hash = vnpaySign(params, cfg.hashSecret);
  return `${cfg.payUrl}?${vnpaySignData(params)}&vnp_SecureHash=${hash}`;
}

/** Kiểm chữ ký kết quả VNPay trả về (query của Return URL hoặc IPN). */
export function verifyVnpay(query: Record<string, any>, secret: string): boolean {
  const received = String(query.vnp_SecureHash || '').toLowerCase();
  if (!received) return false;
  const params: Record<string, string> = {};
  for (const [k, v] of Object.entries(query)) {
    if (!k.startsWith('vnp_') || k === 'vnp_SecureHash' || k === 'vnp_SecureHashType') continue;
    params[k] = String(v);
  }
  const expected = vnpaySign(params, secret);
  return expected.length === received.length && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(received));
}
