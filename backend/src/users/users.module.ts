import { Module } from '@nestjs/common';
import { UsersService } from './users.service';
import { UsersController } from './users.controller';
import { EntitlementsService } from './entitlements';

@Module({
  providers: [UsersService, EntitlementsService],
  controllers: [UsersController],
  exports: [UsersService, EntitlementsService],
})
export class UsersModule {}
