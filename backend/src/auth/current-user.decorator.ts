import { createParamDecorator, ExecutionContext } from '@nestjs/common';
import { FirebaseUser } from './firebase-auth.guard';

export const CurrentUser = createParamDecorator((_data: unknown, ctx: ExecutionContext): FirebaseUser => {
  const req = ctx.switchToHttp().getRequest();
  return req.user;
});
