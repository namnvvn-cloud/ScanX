import { Controller, Get, UseGuards } from '@nestjs/common';
import { FirebaseAuthGuard } from '../auth/firebase-auth.guard';
import { CurrentUser } from '../auth/current-user.decorator';
import { FirebaseUser } from '../auth/firebase-auth.guard';
import { UsersService } from './users.service';

@UseGuards(FirebaseAuthGuard)
@Controller('users')
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  /** GET /users/me — hồ sơ user hiện tại (bao gồm cờ isBusiness). */
  @Get('me')
  async me(@CurrentUser() fbUser: FirebaseUser) {
    return this.usersService.findByFirebaseUid(fbUser.uid);
  }
}
