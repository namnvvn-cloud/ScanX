import { Module } from '@nestjs/common';
import { AppConfigController } from './app-config.controller';
import { AppConfigService } from './app-config.service';
import { UsersModule } from '../users/users.module';

@Module({
  imports: [UsersModule],
  providers: [AppConfigService],
  controllers: [AppConfigController],
})
export class AppConfigModule {}
