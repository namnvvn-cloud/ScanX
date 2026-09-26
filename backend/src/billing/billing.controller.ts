import { Body, Controller, Get, HttpCode, NotFoundException, Post, Query, Req, UseGuards } from '@nestjs/common';
import { IsIn, IsString } from 'class-validator';
import { FirebaseAuthGuard, FirebaseUser } from '../auth/firebase-auth.guard';
import { CurrentUser } from '../auth/current-user.decorator';
import { UsersService } from '../users/users.service';
import { BillingService } from './billing.service';

class CheckoutDto {
  @IsString()
  planId: string;

  @IsIn(['vnpay', 'momo'])
  provider: 'vnpay' | 'momo';

  @IsString()
  returnUrl: string;
}

/**
 * Bán gói Business qua web:
 *  GET  /plans                    — bảng giá (công khai)
 *  GET  /billing/me               — trạng thái Business + lịch sử đơn của user (cần đăng nhập)
 *  POST /payments/checkout        — tạo đơn + trả payUrl (VNPay/MoMo)
 *  GET  /payments/vnpay/ipn       — VNPay server gọi (cấu hình IPN URL trong trang merchant VNPay)
 *  GET  /payments/vnpay/return    — web gửi lại query kết quả để kiểm chữ ký, cập nhật đơn
 *  POST /payments/momo/ipn        — MoMo server gọi
 *  GET  /payments/momo/return     — web gửi lại query kết quả MoMo
 */
@Controller()
export class BillingController {
  constructor(
    private readonly billing: BillingService,
    private readonly users: UsersService,
  ) {}

  @Get('plans')
  async plans() {
    return { plans: await this.billing.listPlans(), providers: this.billing.providers() };
  }

  @UseGuards(FirebaseAuthGuard)
  @Get('billing/me')
  async me(@CurrentUser() fbUser: FirebaseUser) {
    const user = await this.users.upsertFromFirebase(fbUser);
    return { user, orders: await this.billing.listOrdersForUser(user.id) };
  }

  @UseGuards(FirebaseAuthGuard)
  @Post('payments/checkout')
  async checkout(@CurrentUser() fbUser: FirebaseUser, @Body() dto: CheckoutDto, @Req() req: any) {
    const user = await this.users.upsertFromFirebase(fbUser);
    const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
    const ip = forwarded || req.ip || '127.0.0.1';
    const { order, payUrl } = await this.billing.checkout(user.id, dto.planId, dto.provider, dto.returnUrl, ip.replace('::ffff:', ''));
    return { orderCode: order.code, payUrl };
  }

  @Get('payments/vnpay/ipn')
  async vnpayIpn(@Query() query: Record<string, any>) {
    return this.billing.vnpayIpn(query);
  }

  @Get('payments/vnpay/return')
  async vnpayReturn(@Query() query: Record<string, any>) {
    return this.billing.vnpayReturn(query);
  }

  @Post('payments/momo/ipn')
  @HttpCode(204)
  async momoIpn(@Body() body: Record<string, any>) {
    await this.billing.momoIpn(body);
  }

  @Get('payments/momo/return')
  async momoReturn(@Query() query: Record<string, any>) {
    return this.billing.momoReturn(query);
  }

  @UseGuards(FirebaseAuthGuard)
  @Get('billing/orders/by-code')
  async orderByCode(@CurrentUser() fbUser: FirebaseUser, @Query('code') code: string) {
    const user = await this.users.upsertFromFirebase(fbUser);
    const order = await this.billing.findByCode(code);
    if (!order || order.user_id !== user.id) throw new NotFoundException('Không tìm thấy đơn hàng');
    return order;
  }
}
