import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { FirebaseAuthGuard, FirebaseUser } from '../auth/firebase-auth.guard';
import { DatabaseService } from '../database/database.service';

/** Quản trị viên CHÍNH: biến môi trường ADMIN_EMAILS (cách nhau dấu phẩy) — không xoá được từ web. */
export function adminEmails(): string[] {
  return (process.env.ADMIN_EMAILS || '')
    .split(',')
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean);
}

export type AdminRole = 'super' | 'admin';

/**
 * Chỉ cho quản trị viên vào /admin/*: token Firebase hợp lệ + email đã XÁC MINH (đăng nhập Google luôn
 * xác minh) + email thuộc ADMIN_EMAILS (quản trị chính) hoặc bảng `admins` (quản trị phụ do admin chính thêm).
 * Gắn req.adminRole để endpoint quản lý quản trị viên chỉ cho admin chính dùng.
 */
@Injectable()
export class AdminGuard implements CanActivate {
  private readonly auth = new FirebaseAuthGuard();

  constructor(private readonly db: DatabaseService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    await this.auth.canActivate(context);
    const req = context.switchToHttp().getRequest();
    const user: FirebaseUser = req.user;
    const email = (user.email || '').toLowerCase();
    if (!user.emailVerified || !email) throw new ForbiddenException('Tài khoản này không có quyền quản trị');
    let role: AdminRole | null = adminEmails().includes(email) ? 'super' : null;
    if (!role) {
      const { rows } = await this.db.query('select 1 from admins where email = $1', [email]);
      if (rows.length) role = 'admin';
    }
    if (!role) throw new ForbiddenException('Tài khoản này không có quyền quản trị');
    req.adminRole = role;
    return true;
  }
}
