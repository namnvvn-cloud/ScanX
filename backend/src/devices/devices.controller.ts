import { BadRequestException, Body, Controller, HttpCode, Logger, Post, Req } from '@nestjs/common';
import { IsInt, IsOptional, IsString, Matches, MaxLength, Min } from 'class-validator';
import { getFirebaseAdmin } from '../firebase/firebase-admin.provider';
import { DatabaseService } from '../database/database.service';
import { UsersService } from '../users/users.service';

class PingDto {
  /** UUID ngẫu nhiên app tự tạo lần đầu cài (không phải mã máy — gỡ app cài lại là ID mới). */
  @IsString() @Matches(/^[A-Za-z0-9-]{8,64}$/) installId: string;
  @IsOptional() @IsString() @MaxLength(16) platform?: string;
  @IsOptional() @IsInt() @Min(0) versionCode?: number;
  @IsOptional() @IsString() @MaxLength(40) versionName?: string;
  @IsOptional() @IsString() @MaxLength(80) model?: string;
  @IsOptional() @IsString() @MaxLength(40) osVersion?: string;
  @IsOptional() @IsString() @MaxLength(20) locale?: string;
}

/**
 * POST /devices/ping — app gọi mỗi lần mở (không cần đăng nhập) để admin thấy cả người CHƯA đăng ký.
 * Có header Bearer (đã đăng nhập) → gắn thiết bị với tài khoản. Không gửi dữ liệu cá nhân nào khác.
 */
@Controller('devices')
export class DevicesController {
  private readonly logger = new Logger(DevicesController.name);

  constructor(
    private readonly db: DatabaseService,
    private readonly users: UsersService,
  ) {}

  @Post('ping')
  @HttpCode(204)
  async ping(@Body() dto: PingDto, @Req() req: any): Promise<void> {
    const platform = dto.platform === 'ios' ? 'ios' : 'android';
    let userId: string | null = null;
    const header: string | undefined = req.headers['authorization'];
    if (header?.startsWith('Bearer ')) {
      try {
        const d = await getFirebaseAdmin().auth().verifyIdToken(header.substring(7));
        const user = await this.users.upsertFromFirebase({ uid: d.uid, email: d.email, name: d.name, emailVerified: d.email_verified === true });
        userId = user.id;
      } catch {
        // Token hết hạn/sai: vẫn ghi nhận thiết bị như chưa đăng nhập.
      }
    }
    if (!dto.installId) throw new BadRequestException('Thiếu installId');
    await this.db.query(
      `insert into devices (install_id, platform, version_code, version_name, model, os_version, locale, user_id)
       values ($1, $2, $3, $4, $5, $6, $7, $8)
       on conflict (install_id) do update set
         platform = excluded.platform, version_code = excluded.version_code, version_name = excluded.version_name,
         model = excluded.model, os_version = excluded.os_version, locale = excluded.locale,
         user_id = coalesce(excluded.user_id, devices.user_id),
         open_count = devices.open_count + 1, last_seen = now()`,
      [dto.installId, platform, dto.versionCode ?? null, dto.versionName ?? null, dto.model ?? null, dto.osVersion ?? null, dto.locale ?? null, userId],
    );
  }
}
