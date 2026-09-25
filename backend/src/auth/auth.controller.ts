import { Controller, Post, UseGuards } from '@nestjs/common';
import { FirebaseAuthGuard } from './firebase-auth.guard';
import { CurrentUser } from './current-user.decorator';
import { FirebaseUser } from './firebase-auth.guard';
import { UsersService } from '../users/users.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly usersService: UsersService) {}

  /**
   * POST /auth/login
   * Header: Authorization: Bearer <firebase-id-token>
   * Gọi ngay sau khi Android đăng nhập Firebase Auth thành công. Backend xác thực token,
   * tự tạo hồ sơ user trong DB nếu là lần đầu (upsert theo firebaseUid), trả về hồ sơ.
   */
  @UseGuards(FirebaseAuthGuard)
  @Post('login')
  async login(@CurrentUser() user: FirebaseUser) {
    const profile = await this.usersService.upsertFromFirebase(user);
    return { user: profile };
  }
}
