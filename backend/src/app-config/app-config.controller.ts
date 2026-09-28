import { BadRequestException, Body, Controller, Get, Put, Query, UseGuards } from '@nestjs/common';
import { AdminGuard } from '../admin/admin.guard';
import { AppConfigService } from './app-config.service';

/**
 * GET /app/config?platform=android|ios&versionCode=N — công khai, app gọi lúc mở:
 *   { update: UpdateInfo, productInfo }  → nhắc / ép cập nhật + màn "Thông tin sản phẩm".
 */
@Controller()
export class AppConfigController {
  constructor(private readonly config: AppConfigService) {}

  @Get('app/config')
  async appConfig(@Query('platform') platform = 'android', @Query('versionCode') versionCode = '0') {
    const p = platform === 'ios' ? 'ios' : 'android';
    const current = Number.parseInt(versionCode, 10) || 0;
    const [update, productInfo] = await Promise.all([this.config.updateInfo(p, current), this.config.productInfo()]);
    return { update, productInfo };
  }

  /** Web Admin: đọc toàn bộ cấu hình + bản APK mới nhất GitHub đang thấy. */
  @UseGuards(AdminGuard)
  @Get('admin/app-config')
  async adminGet() {
    const [appVersion, productInfo, github] = await Promise.all([
      this.config.versionConfig(),
      this.config.productInfo(),
      this.config.latestGithubRelease(true),
    ]);
    return { appVersion, productInfo, github };
  }

  @UseGuards(AdminGuard)
  @Put('admin/app-config/app_version')
  async putVersion(@Body() body: any) {
    if (!body || typeof body !== 'object' || typeof body.android !== 'object' || typeof body.ios !== 'object') {
      throw new BadRequestException('Cần { android: {...}, ios: {...} }');
    }
    const num = (v: any) => Math.max(0, Math.floor(Number(v) || 0));
    const str = (v: any) => (typeof v === 'string' ? v.trim() : '');
    const value = {
      android: {
        autoFromGithub: body.android.autoFromGithub !== false,
        forceLatest: !!body.android.forceLatest,
        minVersionCode: num(body.android.minVersionCode),
        latestVersionCode: num(body.android.latestVersionCode),
        latestVersionName: str(body.android.latestVersionName),
        downloadUrl: str(body.android.downloadUrl),
        releaseNotes: str(body.android.releaseNotes),
      },
      ios: {
        forceLatest: !!body.ios.forceLatest,
        minBuild: num(body.ios.minBuild),
        latestBuild: num(body.ios.latestBuild),
        latestVersion: str(body.ios.latestVersion),
        downloadUrl: str(body.ios.downloadUrl),
        releaseNotes: str(body.ios.releaseNotes),
      },
    };
    await this.config.set('app_version', value);
    return value;
  }

  @UseGuards(AdminGuard)
  @Put('admin/app-config/product_info')
  async putProductInfo(@Body() body: any) {
    if (!body || typeof body !== 'object') throw new BadRequestException('Dữ liệu không hợp lệ');
    const str = (v: any, max = 2000) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
    const value = {
      appName: str(body.appName, 80) || 'ScanX',
      tagline: str(body.tagline, 200),
      description: str(body.description, 3000),
      features: Array.isArray(body.features) ? body.features.map((f: any) => str(f, 300)).filter(Boolean).slice(0, 30) : [],
      publisher: str(body.publisher, 200),
      website: str(body.website, 300),
      email: str(body.email, 200),
      phone: str(body.phone, 50),
      address: str(body.address, 300),
      privacyUrl: str(body.privacyUrl, 300),
      termsUrl: str(body.termsUrl, 300),
    };
    await this.config.set('product_info', value);
    return value;
  }
}
