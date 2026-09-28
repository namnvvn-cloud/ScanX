import { Controller, Get, UseGuards } from '@nestjs/common';
import { FirebaseAuthGuard } from '../auth/firebase-auth.guard';
import { CurrentUser } from '../auth/current-user.decorator';
import { FirebaseUser } from '../auth/firebase-auth.guard';
import { UsersService } from './users.service';
import { withEntitlements } from './entitlements';

@UseGuards(FirebaseAuthGuard)
@Controller('users')
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  /**
   * GET /users/me — hồ sơ user + `business_active` (đã tính hạn) + `entitlements` (tính năng nào mở,
   * lượt dùng thử). App gọi lúc mở app / sau đăng nhập / khi bấm "Làm mới". Tự tạo hồ sơ nếu chưa có.
   */
  @Get('me')
  async me(@CurrentUser() fbUser: FirebaseUser) {
    return withEntitlements(await this.usersService.upsertFromFirebase(fbUser));
  }
}
