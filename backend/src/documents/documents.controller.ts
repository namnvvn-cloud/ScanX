import {
  Body,
  Controller,
  Delete,
  Get,
  ForbiddenException,
  NotFoundException,
  Param,
  Patch,
  Post,
  UseGuards,
} from '@nestjs/common';
import { FirebaseAuthGuard } from '../auth/firebase-auth.guard';
import { CurrentUser } from '../auth/current-user.decorator';
import { FirebaseUser } from '../auth/firebase-auth.guard';
import { UsersService } from '../users/users.service';
import { EntitlementsService } from '../users/entitlements';
import { DocumentsService } from './documents.service';
import { CreateDocumentDto } from './dto/create-document.dto';
import { UpdateDocumentDto } from './dto/update-document.dto';

/**
 * Toàn bộ endpoint /documents yêu cầu đã gọi /auth/login trước đó (để có record User trong DB).
 * Sync đa thiết bị: Android gọi GET /documents để lấy danh sách + updatedAt, so sánh với local
 * để biết tài liệu nào mới hơn ở server hoặc ở máy.
 */
@UseGuards(FirebaseAuthGuard)
@Controller('documents')
export class DocumentsController {
  constructor(
    private readonly documentsService: DocumentsService,
    private readonly usersService: UsersService,
    private readonly entitlements: EntitlementsService,
  ) {}

  private async resolveUserId(fbUid: string): Promise<string> {
    const user = await this.usersService.findByFirebaseUid(fbUid);
    if (!user) {
      throw new NotFoundException('Chưa có hồ sơ user — gọi POST /auth/login trước');
    }
    return user.id;
  }

  @Get()
  async list(@CurrentUser() fbUser: FirebaseUser) {
    const userId = await this.resolveUserId(fbUser.uid);
    return this.documentsService.list(userId);
  }

  /** Sao lưu đám mây = tính năng Business (tốn dung lượng lưu trữ) → chặn ở server, không chỉ ở app. */
  @Post()
  async create(@CurrentUser() fbUser: FirebaseUser, @Body() dto: CreateDocumentDto) {
    const user = await this.usersService.findByFirebaseUid(fbUser.uid);
    if (!user) throw new NotFoundException('Chưa có hồ sơ user — gọi POST /auth/login trước');
    if (!(await this.entitlements.allowed(user, 'cloud_backup'))) {
      throw new ForbiddenException('Sao lưu đám mây chưa được mở cho tài khoản này (cần gói Business còn hạn)');
    }
    return this.documentsService.createWithUploadUrl(user.id, dto);
  }

  @Get(':id')
  async get(@CurrentUser() fbUser: FirebaseUser, @Param('id') id: string) {
    const userId = await this.resolveUserId(fbUser.uid);
    return this.documentsService.getWithDownloadUrl(userId, id);
  }

  @Patch(':id')
  async update(
    @CurrentUser() fbUser: FirebaseUser,
    @Param('id') id: string,
    @Body() dto: UpdateDocumentDto,
  ) {
    const userId = await this.resolveUserId(fbUser.uid);
    return this.documentsService.update(userId, id, dto);
  }

  @Delete(':id')
  async remove(@CurrentUser() fbUser: FirebaseUser, @Param('id') id: string) {
    const userId = await this.resolveUserId(fbUser.uid);
    await this.documentsService.remove(userId, id);
    return { ok: true };
  }
}
