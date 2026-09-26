import * as crypto from 'crypto';

/**
 * MoMo — API "v2/gateway/api/create" (captureWallet), ký HMAC-SHA256 theo tài liệu MoMo.
 * Sandbox: https://test-payment.momo.vn (tài khoản test công khai trong tài liệu developers.momo.vn).
 */
export interface MomoConfig {
  partnerCode: string;
  accessKey: string;
  secretKey: string;
  endpoint: string;
}

export function momoConfig(): MomoConfig | null {
  const partnerCode = process.env.MOMO_PARTNER_CODE;
  const accessKey = process.env.MOMO_ACCESS_KEY;
  const secretKey = process.env.MOMO_SECRET_KEY;
  if (!partnerCode || !accessKey || !secretKey) return null;
  return {
    partnerCode,
    accessKey,
    secretKey,
    endpoint: process.env.MOMO_ENDPOINT || 'https://test-payment.momo.vn/v2/gateway/api/create',
  };
}

function hmac(raw: string, secret: string): string {
  return crypto.createHmac('sha256', secret).update(raw).digest('hex');
}

export async function createMomoPayment(
  cfg: MomoConfig,
  opts: { orderCode: string; amountVnd: number; orderInfo: string; redirectUrl: string; ipnUrl: string },
): Promise<{ payUrl: string; raw: any }> {
  const requestId = `${opts.orderCode}-${Date.now()}`;
  const requestType = 'captureWallet';
  const extraData = '';
  const raw =
    `accessKey=${cfg.accessKey}&amount=${opts.amountVnd}&extraData=${extraData}&ipnUrl=${opts.ipnUrl}` +
    `&orderId=${opts.orderCode}&orderInfo=${opts.orderInfo}&partnerCode=${cfg.partnerCode}` +
    `&redirectUrl=${opts.redirectUrl}&requestId=${requestId}&requestType=${requestType}`;
  const body = {
    partnerCode: cfg.partnerCode,
    accessKey: cfg.accessKey,
    requestId,
    amount: opts.amountVnd,
    orderId: opts.orderCode,
    orderInfo: opts.orderInfo,
    redirectUrl: opts.redirectUrl,
    ipnUrl: opts.ipnUrl,
    extraData,
    requestType,
    lang: 'vi',
    signature: hmac(raw, cfg.secretKey),
  };
  const res = await fetch(cfg.endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  const json: any = await res.json().catch(() => ({}));
  if (!res.ok || json.resultCode !== 0 || !json.payUrl) {
    throw new Error(`MoMo từ chối tạo giao dịch: ${json.message || res.status} (resultCode ${json.resultCode ?? '?'})`);
  }
  return { payUrl: json.payUrl, raw: json };
}

const RESULT_FIELDS = [
  'accessKey', 'amount', 'extraData', 'message', 'orderId', 'orderInfo', 'orderType',
  'partnerCode', 'payType', 'requestId', 'responseTime', 'resultCode', 'transId',
];

/** Kiểm chữ ký kết quả MoMo (IPN JSON hoặc query của redirectUrl). */
export function verifyMomo(data: Record<string, any>, cfg: MomoConfig): boolean {
  const received = String(data.signature || '');
  if (!received) return false;
  const values: Record<string, any> = { ...data, accessKey: cfg.accessKey };
  const raw = RESULT_FIELDS.map((k) => `${k}=${values[k] ?? ''}`).join('&');
  const expected = hmac(raw, cfg.secretKey);
  return expected.length === received.length && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(received));
}
