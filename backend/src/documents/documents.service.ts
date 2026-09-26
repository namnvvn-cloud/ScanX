import { Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { DatabaseService } from '../database/database.service';
import { DocumentRow } from '../database/entities';
import { R2Service } from '../storage/r2.service';
import { CreateDocumentDto } from './dto/create-document.dto';
import { UpdateDocumentDto } from './dto/update-document.dto';

@Injectable()
export class DocumentsService {
  constructor(
    private readonly db: DatabaseService,
    private readonly r2: R2Service,
  ) {}

  async list(userId: string): Promise<DocumentRow[]> {
    const { rows } = await this.db.query<DocumentRow>(
      'select * from documents where user_id = $1 order by updated_at desc',
      [userId],
    );
    return rows;
  }

  /** Tạo record metadata + trả presigned URL để Android upload file thẳng lên R2 (không qua backend). */
  async createWithUploadUrl(userId: string, dto: CreateDocumentDto) {
    const fileKey = `${userId}/${randomUUID()}.pdf`;
    const mimeType = dto.mimeType || 'application/pdf';
    const { rows } = await this.db.query<DocumentRow>(
      `insert into documents (user_id, title, page_count, mime_type, file_key, file_size)
       values ($1, $2, $3, $4, $5, $6)
       returning *`,
      [userId, dto.title, dto.pageCount, mimeType, fileKey, dto.fileSize ?? null],
    );
    const doc = rows[0];
    const uploadUrl = await this.r2.getUploadUrl(fileKey, mimeType);
    return { document: doc, uploadUrl };
  }

  async getWithDownloadUrl(userId: string, id: string) {
    const doc = await this.findOwned(userId, id);
    const downloadUrl = await this.r2.getDownloadUrl(doc.file_key);
    return { document: doc, downloadUrl };
  }

  async update(userId: string, id: string, dto: UpdateDocumentDto): Promise<DocumentRow> {
    await this.findOwned(userId, id);
    const { rows } = await this.db.query<DocumentRow>(
      `update documents
       set title = coalesce($3, title),
           page_count = coalesce($4, page_count),
           updated_at = now()
       where id = $1 and user_id = $2
       returning *`,
      [id, userId, dto.title ?? null, dto.pageCount ?? null],
    );
    return rows[0];
  }

  async remove(userId: string, id: string): Promise<void> {
    const doc = await this.findOwned(userId, id);
    await this.r2.deleteObject(doc.file_key);
    await this.db.query('delete from documents where id = $1 and user_id = $2', [id, userId]);
  }

  private async findOwned(userId: string, id: string): Promise<DocumentRow> {
    const { rows } = await this.db.query<DocumentRow>(
      'select * from documents where id = $1 and user_id = $2',
      [id, userId],
    );
    if (!rows[0]) {
      throw new NotFoundException('Không tìm thấy tài liệu');
    }
    return rows[0];
  }
}
