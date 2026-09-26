import { Body, Controller, Delete, Get, Param, ParseUUIDPipe, Patch, Post, Query, UseGuards } from '@nestjs/common';
import { IsBoolean, IsInt, IsOptional, IsString, Min } from 'class-validator';
import { AdminGuard } from './admin.guard';
import { AdminService } from './admin.service';

class SetBusinessDto {
  @IsBoolean()
  isBusiness: boolean;

  /** ISO date; null/bỏ trống = không thời hạn. */
  @IsOptional()
  @IsString()
  expiresAt?: string | null;
}

class UpdatePlanDto {
  @IsOptional() @IsString() name?: string;
  @IsOptional() @IsString() description?: string;
  @IsOptional() @IsInt() @Min(0) priceVnd?: number;
  @IsOptional() @IsInt() @Min(1) durationDays?: number;
  @IsOptional() @IsBoolean() active?: boolean;
}

function pageArgs(page?: string, pageSize?: string): [number, number] {
  const p = Math.max(1, parseInt(page || '1', 10) || 1);
  const s = Math.min(100, Math.max(5, parseInt(pageSize || '20', 10) || 20));
  return [p, s];
}

/** API cho Web Admin — mọi route yêu cầu tài khoản quản trị (AdminGuard). */
@UseGuards(AdminGuard)
@Controller('admin')
export class AdminController {
  constructor(private readonly admin: AdminService) {}

  @Get('me')
  me() {
    return { ok: true };
  }

  @Get('stats')
  stats() {
    return this.admin.stats();
  }

  @Get('users')
  users(@Query('q') q = '', @Query('filter') filter = '', @Query('page') page?: string, @Query('pageSize') pageSize?: string) {
    return this.admin.listUsers(q.trim(), filter, ...pageArgs(page, pageSize));
  }

  @Get('users/:id')
  user(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.userDetail(id);
  }

  @Patch('users/:id/business')
  setBusiness(@Param('id', ParseUUIDPipe) id: string, @Body() dto: SetBusinessDto) {
    return this.admin.setBusiness(id, dto.isBusiness, dto.expiresAt ?? null);
  }

  @Get('documents/:id/download')
  download(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.documentDownloadUrl(id);
  }

  @Delete('documents/:id')
  deleteDocument(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.deleteDocument(id);
  }

  @Get('orders')
  orders(@Query('status') status = '', @Query('q') q = '', @Query('page') page?: string, @Query('pageSize') pageSize?: string) {
    return this.admin.listOrders(status, q.trim(), ...pageArgs(page, pageSize));
  }

  @Post('orders/:id/confirm')
  confirm(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.confirmOrder(id);
  }

  @Post('orders/:id/cancel')
  cancel(@Param('id', ParseUUIDPipe) id: string) {
    return this.admin.cancelOrder(id);
  }

  @Get('plans')
  plans() {
    return this.admin.plans();
  }

  @Patch('plans/:id')
  updatePlan(@Param('id') id: string, @Body() dto: UpdatePlanDto) {
    return this.admin.updatePlan(id, dto);
  }
}
