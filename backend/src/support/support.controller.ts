import { Body, Controller, Get, NotFoundException, Param, ParseUUIDPipe, Patch, Post, Query, Req, UseGuards } from '@nestjs/common';
import { IsEmail, IsIn, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';
import { getFirebaseAdmin } from '../firebase/firebase-admin.provider';
import { DatabaseService } from '../database/database.service';
import { UsersService } from '../users/users.service';
import { businessActive } from '../users/entitlements';
import { AdminGuard } from '../admin/admin.guard';

class CreateTicketDto {
  @IsString() @MinLength(3) @MaxLength(150) subject: string;
  @IsString() @MinLength(5) @MaxLength(5000) message: string;
  @IsOptional() @IsEmail() email?: string;
  @IsOptional() @IsString() @MaxLength(64) installId?: string;
  @IsOptional() @IsString() @MaxLength(16) platform?: string;
  @IsOptional() @IsString() @MaxLength(40) appVersion?: string;
  @IsOptional() @IsString() @MaxLength(120) device?: string;
}

class UpdateTicketDto {
  @IsOptional() @IsIn(['open', 'closed']) status?: 'open' | 'closed';
  @IsOptional() @IsString() @MaxLength(5000) adminNote?: string;
}

/**
 * Hỗ trợ khách hàng: app gửi yêu cầu (không bắt buộc đăng nhập; đăng nhập thì gắn tài khoản và
 * Business còn hạn được đánh dấu ƯU TIÊN). Admin xem / đóng / ghi chú trên Web Admin → Hỗ trợ.
 */
@Controller()
export class SupportController {
  constructor(
    private readonly db: DatabaseService,
    private readonly users: UsersService,
  ) {}

  @Post('support/tickets')
  async create(@Body() dto: CreateTicketDto, @Req() req: any) {
    let userId: string | null = null;
    let email = dto.email ?? null;
    let priority = false;
    const header: string | undefined = req.headers['authorization'];
    if (header?.startsWith('Bearer ')) {
      try {
        const d = await getFirebaseAdmin().auth().verifyIdToken(header.substring(7));
        const user = await this.users.upsertFromFirebase({ uid: d.uid, email: d.email, name: d.name, emailVerified: d.email_verified === true });
        userId = user.id;
        email = email ?? user.email;
        priority = businessActive(user);
      } catch {
        // Token lỗi: vẫn nhận yêu cầu như khách chưa đăng nhập.
      }
    }
    const { rows } = await this.db.query(
      `insert into support_tickets (user_id, install_id, email, subject, message, platform, app_version, device, priority)
       values ($1, $2, $3, $4, $5, $6, $7, $8, $9) returning id, priority, created_at`,
      [userId, dto.installId ?? null, email, dto.subject.trim(), dto.message.trim(), dto.platform ?? null, dto.appVersion ?? null, dto.device ?? null, priority],
    );
    return rows[0];
  }

  @UseGuards(AdminGuard)
  @Get('admin/support')
  async list(@Query('status') status = 'open', @Query('page') page = '1') {
    const p = Math.max(1, parseInt(page, 10) || 1);
    const size = 30;
    const params: any[] = [];
    let where = '';
    if (status === 'open' || status === 'closed') {
      params.push(status);
      where = `where t.status = $1`;
    }
    const { rows: c } = await this.db.query(`select count(*)::int as n from support_tickets t ${where}`, params);
    params.push(size, (p - 1) * size);
    const { rows } = await this.db.query(
      `select t.*, u.email as user_email from support_tickets t left join users u on u.id = t.user_id
       ${where} order by (t.status = 'open') desc, t.priority desc, t.created_at desc
       limit $${params.length - 1} offset $${params.length}`,
      params,
    );
    return { items: rows, total: c[0].n, page: p, pageSize: size };
  }

  @UseGuards(AdminGuard)
  @Patch('admin/support/:id')
  async update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateTicketDto) {
    const { rows } = await this.db.query(
      `update support_tickets set status = coalesce($2, status), admin_note = coalesce($3, admin_note), updated_at = now()
       where id = $1 returning *`,
      [id, dto.status ?? null, dto.adminNote ?? null],
    );
    if (!rows[0]) throw new NotFoundException('Không tìm thấy yêu cầu');
    return rows[0];
  }
}
