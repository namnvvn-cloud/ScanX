import { Module } from '@nestjs/common';
import { UsersModule } from '../users/users.module';
import { SupportController } from './support.controller';

@Module({
  imports: [UsersModule],
  controllers: [SupportController],
})
export class SupportModule {}
