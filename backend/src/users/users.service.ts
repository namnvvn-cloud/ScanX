import { Injectable } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';
import { UserRow } from '../database/entities';
import { FirebaseUser } from '../auth/firebase-auth.guard';

@Injectable()
export class UsersService {
  constructor(private readonly db: DatabaseService) {}

  async upsertFromFirebase(fbUser: FirebaseUser): Promise<UserRow> {
    const { rows } = await this.db.query<UserRow>(
      `insert into users (firebase_uid, email, display_name)
       values ($1, $2, $3)
       on conflict (firebase_uid)
       do update set email = excluded.email, display_name = coalesce(excluded.display_name, users.display_name),
                     last_login_at = now(), updated_at = now()
       returning *`,
      [fbUser.uid, fbUser.email ?? null, fbUser.name ?? null],
    );
    return rows[0];
  }

  async findByFirebaseUid(uid: string): Promise<UserRow | null> {
    const { rows } = await this.db.query<UserRow>('select * from users where firebase_uid = $1', [uid]);
    return rows[0] ?? null;
  }

  /** Dùng cho Web Admin (Phase 2) khi duyệt/kích hoạt gói Business thủ công. */
  async setBusinessStatus(userId: string, isBusiness: boolean, expiresAt?: Date | null): Promise<UserRow> {
    const { rows } = await this.db.query<UserRow>(
      `update users set is_business = $2, business_expires_at = $3, updated_at = now()
       where id = $1
       returning *`,
      [userId, isBusiness, expiresAt ?? null],
    );
    return rows[0];
  }
}
