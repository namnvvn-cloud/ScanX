import { Module } from '@nestjs/common';
import { BillingModule } from '../billing/billing.module';
import { UsersModule } from '../users/users.module';
import { R2Service } from '../storage/r2.service';
import { AdminController } from './admin.controller';
import { AdminService } from './admin.service';

@Module({
  imports: [BillingModule, UsersModule],
  providers: [AdminService, R2Service],
  controllers: [AdminController],
})
export class AdminModule {}
