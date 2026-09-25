import { Module } from '@nestjs/common';
import { DocumentsService } from './documents.service';
import { DocumentsController } from './documents.controller';
import { R2Service } from '../storage/r2.service';
import { UsersModule } from '../users/users.module';

@Module({
  imports: [UsersModule],
  providers: [DocumentsService, R2Service],
  controllers: [DocumentsController],
})
export class DocumentsModule {}
