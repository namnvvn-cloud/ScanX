import { BadRequestException, Injectable, Logger, NotFoundException, ServiceUnavailableException } from '@nestjs/common';
import { randomBytes } from 'crypto';
import { DatabaseService } from '../database/database.service';
import { OrderRow, PaymentProvider, PlanRow } from '../database/entities';
import { buildVnpayUrl, verifyVnpay, vnpayConfig } from './vnpay';
import { createMomoPayment, momoConfig, verifyMomo } from './momo';

export interface PaymentResult {
  ok: boolean;
  orderCode: string | null;
  status: string;
  message: string;
}

/**
 * Gói Business + đơn hàng + thanh toán (VNPay / MoMo). Đơn "paid" → kích hoạt/gia hạn Business cho user
 * trong CÙNG transaction, idempotent (IPN và Return URL có thể tới 2 lần, chỉ lần đầu được tính).
 * Tách bạch khỏi IAP của App Store/Google Play (bán qua web — tránh vi phạm Guideline 3.1.1).
 */
@Injectable()
export class BillingService {
  private readonly logger = new Logger(BillingService.name);

  constructor(private readonly db: DatabaseService) {}

  async listPlans(includeInactive = false): Promise<PlanRow[]> {
    const { rows } = await this.db.query<PlanRow>(
      `select * from plans ${includeInactive ? '' : 'where active'} order by sort_order, price_vnd`,
    );
    return rows;
  }

  providers(): { vnpay: boolean; momo: boolean } {
    return { vnpay: vnpayConfig() !== null, momo: momoConfig() !== null };
  }

  async listOrdersForUser(userId: string): Promise<OrderRow[]> {
    const { rows } = await this.db.query<OrderRow>(
      'select * from orders where user_id = $1 order by created_at desc limit 50',
      [userId],
    );
    return rows;
  }

  /** Tạo đơn + URL thanh toán. returnUrl = trang kết quả trên web (VNPay/MoMo chuyển người dùng về đó). */
  async checkout(
    userId: string,
    planId: string,
    provider: PaymentProvider,
    returnUrl: string,
    ipAddr: string,
  ): Promise<{ order: OrderRow; payUrl: string }> {
    const { rows: plans } = await this.db.query<PlanRow>('select * from plans where id = $1 and active', [planId]);
    const plan = plans[0];
    if (!plan) throw new NotFoundException('Gói không tồn tại hoặc đã ngừng bán');
    if (!/^https?:\/\//.test(returnUrl)) throw new BadRequestException('returnUrl không hợp lệ');

    const code = `SX${Date.now().toString().slice(-10)}${randomBytes(2).toString('hex').toUpperCase()}`;
    const { rows } = await this.db.query<OrderRow>(
      `insert into orders (code, user_id, plan_id, amount_vnd, duration_days, provider)
       values ($1, $2, $3, $4, $5, $6) returning *`,
      [code, userId, plan.id, plan.price_vnd, plan.duration_days, provider],
    );
    const order = rows[0];
    const orderInfo = `Thanh toan ${plan.id} don ${code}`;

    if (provider === 'vnpay') {
      const cfg = vnpayConfig();
      if (!cfg) throw new ServiceUnavailableException('Chưa cấu hình VNPay (VNPAY_TMN_CODE, VNPAY_HASH_SECRET)');
      const payUrl = buildVnpayUrl(cfg, { orderCode: code, amountVnd: plan.price_vnd, orderInfo, returnUrl, ipAddr });
      return { order, payUrl };
    }
    if (provider === 'momo') {
      const cfg = momoConfig();
      if (!cfg) throw new ServiceUnavailableException('Chưa cấu hình MoMo (MOMO_PARTNER_CODE, MOMO_ACCESS_KEY, MOMO_SECRET_KEY)');
      const apiUrl = (process.env.PUBLIC_API_URL || '').replace(/\/$/, '');
      if (!apiUrl) throw new ServiceUnavailableException('Chưa cấu hình PUBLIC_API_URL (địa chỉ backend cho MoMo gọi IPN)');
      try {
        const { payUrl } = await createMomoPayment(cfg, {
          orderCode: code,
          amountVnd: plan.price_vnd,
          orderInfo,
          redirectUrl: returnUrl,
          ipnUrl: `${apiUrl}/payments/momo/ipn`,
        });
        return { order, payUrl };
      } catch (err: any) {
        await this.markFailed(code, { error: err.message });
        throw new BadRequestException(err.message);
      }
    }
    throw new BadRequestException('Cổng thanh toán không hỗ trợ');
  }

  // ---------------------------------------------------------------- VNPay

  /** IPN (VNPay server gọi) — trả đúng mã RspCode theo tài liệu VNPay. */
  async vnpayIpn(query: Record<string, any>): Promise<{ RspCode: string; Message: string }> {
    const cfg = vnpayConfig();
    if (!cfg || !verifyVnpay(query, cfg.hashSecret)) return { RspCode: '97', Message: 'Invalid signature' };
    const order = await this.findByCode(String(query.vnp_TxnRef || ''));
    if (!order) return { RspCode: '01', Message: 'Order not found' };
    if (Number(query.vnp_Amount) !== order.amount_vnd * 100) return { RspCode: '04', Message: 'Invalid amount' };
    if (order.status !== 'pending') return { RspCode: '02', Message: 'Order already confirmed' };
    const success = query.vnp_ResponseCode === '00' && query.vnp_TransactionStatus === '00';
    if (success) {
      await this.markPaid(order.code, String(query.vnp_TransactionNo || ''), query);
    } else {
      await this.markFailed(order.code, query);
    }
    return { RspCode: '00', Message: 'Confirm Success' };
  }

  /**
   * Return URL: web gửi lại nguyên query VNPay trả về để backend kiểm chữ ký. Dữ liệu đã ký bởi VNPay
   * nên cập nhật đơn ở đây cũng an toàn (quan trọng ở sandbox khi IPN chưa cấu hình được).
   */
  async vnpayReturn(query: Record<string, any>): Promise<PaymentResult> {
    const cfg = vnpayConfig();
    if (!cfg || !verifyVnpay(query, cfg.hashSecret)) {
      return { ok: false, orderCode: null, status: 'invalid', message: 'Chữ ký VNPay không hợp lệ' };
    }
    const code = String(query.vnp_TxnRef || '');
    const order = await this.findByCode(code);
    if (!order) return { ok: false, orderCode: code, status: 'not_found', message: 'Không tìm thấy đơn hàng' };
    const success = query.vnp_ResponseCode === '00' && query.vnp_TransactionStatus === '00';
    if (order.status === 'pending' && Number(query.vnp_Amount) === order.amount_vnd * 100) {
      if (success) await this.markPaid(code, String(query.vnp_TransactionNo || ''), query);
      else await this.markFailed(code, query);
    }
    const fresh = await this.findByCode(code);
    return {
      ok: fresh?.status === 'paid',
      orderCode: code,
      status: fresh?.status ?? 'unknown',
      message: fresh?.status === 'paid' ? 'Thanh toán thành công — gói Business đã được kích hoạt' : `Thanh toán chưa thành công (mã VNPay ${query.vnp_ResponseCode})`,
    };
  }

  // ---------------------------------------------------------------- MoMo

  async momoIpn(body: Record<string, any>): Promise<void> {
    const cfg = momoConfig();
    if (!cfg || !verifyMomo(body, cfg)) {
      this.logger.warn('MoMo IPN sai chữ ký — bỏ qua');
      return;
    }
    await this.applyMomoResult(body);
  }

  async momoReturn(query: Record<string, any>): Promise<PaymentResult> {
    const cfg = momoConfig();
    if (!cfg || !verifyMomo(query, cfg)) {
      return { ok: false, orderCode: null, status: 'invalid', message: 'Chữ ký MoMo không hợp lệ' };
    }
    await this.applyMomoResult(query);
    const code = String(query.orderId || '');
    const fresh = await this.findByCode(code);
    return {
      ok: fresh?.status === 'paid',
      orderCode: code,
      status: fresh?.status ?? 'unknown',
      message: fresh?.status === 'paid' ? 'Thanh toán thành công — gói Business đã được kích hoạt' : `Thanh toán chưa thành công (${query.message || 'MoMo'})`,
    };
  }

  private async applyMomoResult(data: Record<string, any>) {
    const order = await this.findByCode(String(data.orderId || ''));
    if (!order || order.status !== 'pending') return;
    if (Number(data.amount) !== order.amount_vnd) return;
    if (Number(data.resultCode) === 0) {
      await this.markPaid(order.code, String(data.transId || ''), data);
    } else {
      await this.markFailed(order.code, data);
    }
  }

  // ---------------------------------------------------------------- Kích hoạt

  async findByCode(code: string): Promise<OrderRow | null> {
    if (!code) return null;
    const { rows } = await this.db.query<OrderRow>('select * from orders where code = $1', [code]);
    return rows[0] ?? null;
  }

  /** pending → paid + gia hạn Business (cộng dồn từ ngày hết hạn hiện tại nếu còn hạn). Idempotent. */
  async markPaid(code: string, providerTxnId: string, response: any): Promise<boolean> {
    return this.db.transaction(async (client) => {
      const { rows } = await client.query<OrderRow>(
        `update orders set status = 'paid', paid_at = now(), provider_txn_id = $2, provider_response = $3, updated_at = now()
         where code = $1 and status = 'pending' returning *`,
        [code, providerTxnId || null, JSON.stringify(response ?? {})],
      );
      const order = rows[0];
      if (!order) return false;
      await client.query(
        `update users set is_business = true,
           business_expires_at = greatest(now(), coalesce(business_expires_at, now())) + make_interval(days => $2),
           updated_at = now()
         where id = $1`,
        [order.user_id, order.duration_days],
      );
      this.logger.log(`Đơn ${code} đã thanh toán — kích hoạt Business ${order.duration_days} ngày`);
      return true;
    });
  }

  async markFailed(code: string, response: any): Promise<void> {
    await this.db.query(
      `update orders set status = 'failed', provider_response = $2, updated_at = now() where code = $1 and status = 'pending'`,
      [code, JSON.stringify(response ?? {})],
    );
  }
}
