import { Injectable } from '@nestjs/common';
import {
  DeleteObjectCommand,
  GetObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

/**
 * Storage tương thích API S3 (mặc định trỏ vào Supabase Storage — free tier, không cần thẻ;
 * dùng chung account Supabase đã có sẵn cho Postgres). Dùng thẳng @aws-sdk/client-s3, chỉ
 * cần đổi ENDPOINT/REGION/khoá trong .env là chuyển sang provider S3-compatible khác được
 * (Cloudflare R2, Backblaze B2...) mà không phải sửa code.
 * Backend KHÔNG proxy file qua chính nó: chỉ cấp presigned URL để Android upload/download
 * thẳng lên/xuống storage, đỡ tải cho server và nhanh hơn cho user.
 */
@Injectable()
export class R2Service {
  private readonly client: S3Client;
  private readonly bucket: string;

  constructor() {
    this.bucket = process.env.STORAGE_BUCKET || '';
    this.client = new S3Client({
      region: process.env.STORAGE_REGION || 'us-east-1',
      endpoint: process.env.STORAGE_ENDPOINT,
      forcePathStyle: true,
      credentials: {
        accessKeyId: process.env.STORAGE_ACCESS_KEY_ID || '',
        secretAccessKey: process.env.STORAGE_SECRET_ACCESS_KEY || '',
      },
    });
  }

  async getUploadUrl(key: string, contentType = 'application/pdf'): Promise<string> {
    const cmd = new PutObjectCommand({ Bucket: this.bucket, Key: key, ContentType: contentType });
    return getSignedUrl(this.client, cmd, { expiresIn: 900 });
  }

  async getDownloadUrl(key: string): Promise<string> {
    const cmd = new GetObjectCommand({ Bucket: this.bucket, Key: key });
    return getSignedUrl(this.client, cmd, { expiresIn: 900 });
  }

  async deleteObject(key: string): Promise<void> {
    await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
  }
}
