import { Module } from '@nestjs/common';
import { BillingModule } from '../billing/billing.module';
import { R2Service } from '../storage/r2.service';
import { AdminController } from './admin.controller';
import { AdminService } from './admin.service';

@Module({
  imports: [BillingModule],
  providers: [AdminService, R2Service],
  controllers: [AdminController],
})
export class AdminModule {}
