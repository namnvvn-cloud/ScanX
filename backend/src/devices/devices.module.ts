import { Module } from '@nestjs/common';
import { UsersModule } from '../users/users.module';
import { DevicesController } from './devices.controller';

@Module({
  imports: [UsersModule],
  controllers: [DevicesController],
})
export class DevicesModule {}
