import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { FirebaseAuthGuard, FirebaseUser } from '../auth/firebase-auth.guard';

/** Danh sách email quản trị (biến môi trường ADMIN_EMAILS, cách nhau bằng dấu phẩy). */
export function adminEmails(): string[] {
  return (process.env.ADMIN_EMAILS || '')
    .split(',')
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean);
}

/**
 * Chỉ cho quản trị viên vào /admin/*: token Firebase hợp lệ + email đã XÁC MINH (đăng nhập Google
 * luôn xác minh; Email/Password chưa xác minh bị từ chối — tránh ai đó đăng ký trước bằng email admin)
 * + email nằm trong ADMIN_EMAILS.
 */
@Injectable()
export class AdminGuard implements CanActivate {
  private readonly auth = new FirebaseAuthGuard();

  async canActivate(context: ExecutionContext): Promise<boolean> {
    await this.auth.canActivate(context);
    const user: FirebaseUser = context.switchToHttp().getRequest().user;
    const email = (user.email || '').toLowerCase();
    if (!user.emailVerified || !email || !adminEmails().includes(email)) {
      throw new ForbiddenException('Tài khoản này không có quyền quản trị');
    }
    return true;
  }
}
