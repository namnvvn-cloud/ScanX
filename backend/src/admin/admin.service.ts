import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';
import { DocumentRow, OrderRow, PlanRow, UserRow } from '../database/entities';
import { R2Service } from '../storage/r2.service';
import { BillingService } from '../billing/billing.service';

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

@Injectable()
export class AdminService {
  constructor(
    private readonly db: DatabaseService,
    private readonly storage: R2Service,
    private readonly billing: BillingService,
  ) {}

  /** Số liệu tổng quan + chuỗi 30 ngày (đăng ký mới, doanh thu) cho Dashboard. */
  async stats() {
    const { rows: totals } = await this.db.query(`
      select
        (select count(*) from users)::int as users,
        (select count(*) from users where created_at > now() - interval '30 days')::int as users_30d,
        (select count(*) from users where is_business and (business_expires_at is null or business_expires_at > now()))::int as business_active,
        (select count(*) from users where last_login_at > now() - interval '7 days')::int as active_7d,
        (select count(*) from documents)::int as documents,
        (select coalesce(sum(page_count), 0) from documents)::bigint as pages,
        (select coalesce(sum(file_size), 0) from documents)::bigint as storage_bytes,
        (select count(*) from orders where status = 'paid')::int as orders_paid,
        (select count(*) from orders where status = 'pending')::int as orders_pending,
        (select coalesce(sum(amount_vnd), 0) from orders where status = 'paid')::bigint as revenue_total,
        (select coalesce(sum(amount_vnd), 0) from orders where status = 'paid' and paid_at > now() - interval '30 days')::bigint as revenue_30d
    `);
    const { rows: series } = await this.db.query(`
      with days as (
        select generate_series((now() at time zone 'Asia/Ho_Chi_Minh')::date - 29, (now() at time zone 'Asia/Ho_Chi_Minh')::date, interval '1 day')::date as day
      )
      select to_char(d.day, 'YYYY-MM-DD') as day,
        (select count(*) from users u where (u.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.day)::int as signups,
        (select count(*) from documents x where (x.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.day)::int as documents,
        (select coalesce(sum(o.amount_vnd), 0) from orders o where o.status = 'paid' and (o.paid_at at time zone 'Asia/Ho_Chi_Minh')::date = d.day)::bigint as revenue
      from days d order by d.day
    `);
    const t = totals[0];
    return {
      totals: {
        ...t,
        pages: Number(t.pages),
        storage_bytes: Number(t.storage_bytes),
        revenue_total: Number(t.revenue_total),
        revenue_30d: Number(t.revenue_30d),
      },
      series: series.map((r: any) => ({ ...r, revenue: Number(r.revenue) })),
    };
  }

  async listUsers(q: string, filter: string, page: number, pageSize: number): Promise<Page<any>> {
    const where: string[] = [];
    const params: any[] = [];
    if (q) {
      params.push(`%${q.toLowerCase()}%`);
      where.push(`(lower(coalesce(u.email, '')) like $${params.length} or lower(coalesce(u.display_name, '')) like $${params.length})`);
    }
    if (filter === 'business') where.push(`u.is_business and (u.business_expires_at is null or u.business_expires_at > now())`);
    if (filter === 'free') where.push(`not (u.is_business and (u.business_expires_at is null or u.business_expires_at > now()))`);
    const whereSql = where.length ? `where ${where.join(' and ')}` : '';
    const { rows: countRows } = await this.db.query(`select count(*)::int as n from users u ${whereSql}`, params);
    params.push(pageSize, (page - 1) * pageSize);
    const { rows } = await this.db.query(
      `select u.*,
         (select count(*) from documents d where d.user_id = u.id)::int as document_count,
         (select coalesce(sum(file_size), 0) from documents d where d.user_id = u.id)::bigint as storage_bytes
       from users u ${whereSql}
       order by u.created_at desc
       limit $${params.length - 1} offset $${params.length}`,
      params,
    );
    return {
      items: rows.map((r: any) => ({ ...r, storage_bytes: Number(r.storage_bytes) })),
      total: countRows[0].n,
      page,
      pageSize,
    };
  }

  async userDetail(id: string) {
    const { rows } = await this.db.query<UserRow>('select * from users where id = $1', [id]);
    if (!rows[0]) throw new NotFoundException('Không tìm thấy người dùng');
    const { rows: documents } = await this.db.query<DocumentRow>(
      'select * from documents where user_id = $1 order by created_at desc',
      [id],
    );
    const { rows: orders } = await this.db.query<OrderRow>(
      'select * from orders where user_id = $1 order by created_at desc',
      [id],
    );
    return { user: rows[0], documents, orders };
  }

  async setBusiness(id: string, isBusiness: boolean, expiresAt: string | null) {
    let expires: Date | null = null;
    if (expiresAt) {
      expires = new Date(expiresAt);
      if (Number.isNaN(expires.getTime())) throw new BadRequestException('Ngày hết hạn không hợp lệ');
    }
    const { rows } = await this.db.query<UserRow>(
      `update users set is_business = $2, business_expires_at = $3, updated_at = now() where id = $1 returning *`,
      [id, isBusiness, isBusiness ? expires : null],
    );
    if (!rows[0]) throw new NotFoundException('Không tìm thấy người dùng');
    return rows[0];
  }

  async documentDownloadUrl(id: string) {
    const { rows } = await this.db.query<DocumentRow>('select * from documents where id = $1', [id]);
    if (!rows[0]) throw new NotFoundException('Không tìm thấy tài liệu');
    return { document: rows[0], downloadUrl: await this.storage.getDownloadUrl(rows[0].file_key) };
  }

  async deleteDocument(id: string) {
    const { rows } = await this.db.query<DocumentRow>('select * from documents where id = $1', [id]);
    if (!rows[0]) throw new NotFoundException('Không tìm thấy tài liệu');
    await this.storage.deleteObject(rows[0].file_key).catch(() => undefined);
    await this.db.query('delete from documents where id = $1', [id]);
    return { ok: true };
  }

  async listOrders(status: string, q: string, page: number, pageSize: number): Promise<Page<any>> {
    const where: string[] = [];
    const params: any[] = [];
    if (status) {
      params.push(status);
      where.push(`o.status = $${params.length}`);
    }
    if (q) {
      params.push(`%${q.toLowerCase()}%`);
      where.push(`(lower(o.code) like $${params.length} or lower(coalesce(u.email, '')) like $${params.length})`);
    }
    const whereSql = where.length ? `where ${where.join(' and ')}` : '';
    const { rows: countRows } = await this.db.query(
      `select count(*)::int as n from orders o join users u on u.id = o.user_id ${whereSql}`,
      params,
    );
    params.push(pageSize, (page - 1) * pageSize);
    const { rows } = await this.db.query(
      `select o.*, u.email as user_email, p.name as plan_name
       from orders o join users u on u.id = o.user_id join plans p on p.id = o.plan_id
       ${whereSql}
       order by o.created_at desc
       limit $${params.length - 1} offset $${params.length}`,
      params,
    );
    return { items: rows, total: countRows[0].n, page, pageSize };
  }

  /** Xác nhận tay (vd khách chuyển khoản, hoặc IPN không tới) → kích hoạt Business như thanh toán thật. */
  async confirmOrder(id: string) {
    const { rows } = await this.db.query<OrderRow>('select * from orders where id = $1', [id]);
    const order = rows[0];
    if (!order) throw new NotFoundException('Không tìm thấy đơn hàng');
    if (order.status !== 'pending') throw new BadRequestException(`Đơn đang ở trạng thái "${order.status}", không xác nhận được`);
    await this.billing.markPaid(order.code, 'manual', { confirmedBy: 'admin' });
    return { ok: true };
  }

  async cancelOrder(id: string) {
    const { rows } = await this.db.query<OrderRow>(
      `update orders set status = 'cancelled', updated_at = now() where id = $1 and status = 'pending' returning *`,
      [id],
    );
    if (!rows[0]) throw new BadRequestException('Chỉ huỷ được đơn đang chờ thanh toán');
    return rows[0];
  }

  async plans(): Promise<PlanRow[]> {
    return this.billing.listPlans(true);
  }

  async updatePlan(id: string, data: { name?: string; description?: string; priceVnd?: number; durationDays?: number; active?: boolean }) {
    const { rows } = await this.db.query<PlanRow>(
      `update plans set
         name = coalesce($2, name),
         description = coalesce($3, description),
         price_vnd = coalesce($4, price_vnd),
         duration_days = coalesce($5, duration_days),
         active = coalesce($6, active),
         updated_at = now()
       where id = $1 returning *`,
      [id, data.name ?? null, data.description ?? null, data.priceVnd ?? null, data.durationDays ?? null, data.active ?? null],
    );
    if (!rows[0]) throw new NotFoundException('Không tìm thấy gói');
    return rows[0];
  }
}
