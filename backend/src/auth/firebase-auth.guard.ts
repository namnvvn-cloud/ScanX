import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { getFirebaseAdmin } from '../firebase/firebase-admin.provider';

export interface FirebaseUser {
  uid: string;
  email?: string;
  name?: string;
  emailVerified?: boolean;
}

/**
 * Guard xác thực mọi request bằng Firebase ID token.
 * Client (Android/iOS/Web) gửi header: Authorization: Bearer <firebase-id-token>
 */
@Injectable()
export class FirebaseAuthGuard implements CanActivate {
  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest();
    const header: string | undefined = req.headers['authorization'];
    if (!header || !header.startsWith('Bearer ')) {
      throw new UnauthorizedException('Thiếu Authorization: Bearer <idToken>');
    }
    const idToken = header.substring('Bearer '.length);
    try {
      const decoded = await getFirebaseAdmin().auth().verifyIdToken(idToken);
      req.user = {
        uid: decoded.uid,
        email: decoded.email,
        name: decoded.name,
        emailVerified: decoded.email_verified === true,
      } as FirebaseUser;
      return true;
    } catch {
      throw new UnauthorizedException('Token không hợp lệ hoặc đã hết hạn');
    }
  }
}
