import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { Pool, PoolClient, QueryResult, QueryResultRow } from 'pg';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Wrapper mỏng quanh pg.Pool — dùng SQL thuần thay vì ORM để tránh phụ thuộc binary native
 * (kiểu Prisma engine) phải tải về lúc build/deploy, đơn giản hoá triển khai trên free-tier
 * (Render/Fly) và trong môi trường mạng bị giới hạn.
 *
 * Lúc khởi động tự chạy mọi file migrations/*.sql (đều idempotent) → deploy lên Render là schema
 * tự cập nhật, không cần chạy tay `npm run db:migrate` trên máy.
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly pool: Pool;
  private readonly logger = new Logger(DatabaseService.name);

  constructor() {
    this.pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      ssl: process.env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: false },
    });
  }

  async onModuleInit() {
    if (process.env.SKIP_MIGRATIONS === 'true' || !process.env.DATABASE_URL) return;
    const candidates = [path.resolve(__dirname, '..', '..', 'migrations'), path.resolve(process.cwd(), 'migrations')];
    const dir = candidates.find((d) => fs.existsSync(d));
    if (!dir) {
      this.logger.warn('Không tìm thấy thư mục migrations — bỏ qua tự migrate');
      return;
    }
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()) {
      await this.pool.query(fs.readFileSync(path.join(dir, file), 'utf8'));
      this.logger.log(`Migrate ${file} ✔`);
    }
  }

  query<T extends QueryResultRow = any>(text: string, params?: any[]): Promise<QueryResult<T>> {
    return this.pool.query<T>(text, params);
  }

  /** Chạy nhiều câu lệnh trong 1 transaction (tự ROLLBACK nếu lỗi). */
  async transaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('begin');
      const result = await fn(client);
      await client.query('commit');
      return result;
    } catch (err) {
      await client.query('rollback');
      throw err;
    } finally {
      client.release();
    }
  }

  async onModuleDestroy() {
    await this.pool.end();
  }
}
