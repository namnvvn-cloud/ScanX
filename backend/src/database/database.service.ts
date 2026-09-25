import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool, QueryResult, QueryResultRow } from 'pg';

/**
 * Wrapper mỏng quanh pg.Pool — dùng SQL thuần thay vì ORM để tránh phụ thuộc binary native
 * (kiểu Prisma engine) phải tải về lúc build/deploy, đơn giản hoá triển khai trên free-tier
 * (Render/Fly) và trong môi trường mạng bị giới hạn.
 */
@Injectable()
export class DatabaseService implements OnModuleDestroy {
  private readonly pool: Pool;

  constructor() {
    this.pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      ssl: process.env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: false },
    });
  }

  query<T extends QueryResultRow = any>(text: string, params?: any[]): Promise<QueryResult<T>> {
    return this.pool.query<T>(text, params);
  }

  async onModuleDestroy() {
    await this.pool.end();
  }
}
