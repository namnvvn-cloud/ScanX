import { Injectable, Logger } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';

export interface AndroidVersionPolicy {
  /** Tự lấy bản mới nhất từ GitHub Releases (APK do CI build, tag debug-build-N = versionCode N). */
  autoFromGithub: boolean;
  /** true = mọi bản cũ hơn bản mới nhất đều bị ép cập nhật; false = chỉ ép khi < minVersionCode. */
  forceLatest: boolean;
  minVersionCode: number;
  /** Nhập tay (vd. khi phát hành qua Google Play); lấy max với GitHub nếu autoFromGithub. */
  latestVersionCode: number;
  latestVersionName: string;
  /** Để trống = link APK trên GitHub Release; điền link Google Play khi đã lên Store. */
  downloadUrl: string;
  releaseNotes: string;
}

export interface IosVersionPolicy {
  forceLatest: boolean;
  minBuild: number;
  latestBuild: number;
  latestVersion: string;
  downloadUrl: string;
  releaseNotes: string;
}

export interface AppVersionConfig {
  android: AndroidVersionPolicy;
  ios: IosVersionPolicy;
}

export interface ProductInfo {
  appName: string;
  tagline: string;
  description: string;
  features: string[];
  publisher: string;
  website: string;
  email: string;
  phone: string;
  address: string;
  privacyUrl: string;
  termsUrl: string;
}

export interface GithubRelease {
  versionCode: number;
  versionName: string;
  downloadUrl: string;
  title: string;
  publishedAt: string;
}

export interface UpdateInfo {
  platform: 'android' | 'ios';
  currentVersionCode: number;
  latestVersionCode: number;
  latestVersionName: string;
  /** Bản thấp nhất được phép dùng; app < mức này phải cập nhật mới dùng tiếp. */
  requiredVersionCode: number;
  updateAvailable: boolean;
  forceUpdate: boolean;
  downloadUrl: string;
  releaseNotes: string;
}

const DEFAULT_VERSION: AppVersionConfig = {
  android: { autoFromGithub: true, forceLatest: true, minVersionCode: 0, latestVersionCode: 0, latestVersionName: '', downloadUrl: '', releaseNotes: '' },
  ios: { forceLatest: false, minBuild: 0, latestBuild: 0, latestVersion: '', downloadUrl: '', releaseNotes: '' },
};

const GITHUB_REPO = process.env.GITHUB_RELEASES_REPO || 'namnvvn-cloud/ScanX';
const GITHUB_CACHE_MS = 5 * 60_000;

@Injectable()
export class AppConfigService {
  private readonly logger = new Logger(AppConfigService.name);
  private githubCache: { at: number; value: GithubRelease | null } = { at: 0, value: null };

  constructor(private readonly db: DatabaseService) {}

  async get<T>(key: string, fallback: T): Promise<T> {
    const { rows } = await this.db.query<{ value: T }>('select value from app_config where key = $1', [key]);
    return rows[0]?.value ?? fallback;
  }

  async set(key: string, value: unknown): Promise<void> {
    await this.db.query(
      `insert into app_config (key, value, updated_at) values ($1, $2, now())
       on conflict (key) do update set value = excluded.value, updated_at = now()`,
      [key, JSON.stringify(value)],
    );
  }

  async versionConfig(): Promise<AppVersionConfig> {
    const v = await this.get<Partial<AppVersionConfig>>('app_version', DEFAULT_VERSION);
    return {
      android: { ...DEFAULT_VERSION.android, ...(v.android ?? {}) },
      ios: { ...DEFAULT_VERSION.ios, ...(v.ios ?? {}) },
    };
  }

  async productInfo(): Promise<ProductInfo> {
    return this.get<ProductInfo>('product_info', {
      appName: 'ScanX', tagline: '', description: '', features: [], publisher: '', website: '', email: '', phone: '', address: '', privacyUrl: '', termsUrl: '',
    });
  }

  /** Bản APK mới nhất do CI đăng trên GitHub Releases (tag debug-build-N, có file .apk). Cache 5 phút. */
  async latestGithubRelease(force = false): Promise<GithubRelease | null> {
    if (!force && Date.now() - this.githubCache.at < GITHUB_CACHE_MS) return this.githubCache.value;
    try {
      const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=15`, {
        headers: { 'User-Agent': 'scanx-backend', Accept: 'application/vnd.github+json' },
        signal: AbortSignal.timeout(8000),
      });
      if (!res.ok) throw new Error(`GitHub HTTP ${res.status}`);
      const list = (await res.json()) as any[];
      let best: GithubRelease | null = null;
      for (const r of list) {
        const m = /^debug-build-(\d+)$/.exec(String(r?.tag_name ?? ''));
        const apk = (r?.assets ?? []).find((a: any) => String(a?.name ?? '').endsWith('.apk') && a?.state === 'uploaded');
        if (!m || r.draft || !apk) continue;
        const code = Number(m[1]);
        if (!best || code > best.versionCode) {
          best = {
            versionCode: code,
            versionName: `0.2.${code}`,
            downloadUrl: apk.browser_download_url,
            title: String(r.name ?? ''),
            publishedAt: String(r.published_at ?? ''),
          };
        }
      }
      this.githubCache = { at: Date.now(), value: best };
    } catch (err: any) {
      // Giữ kết quả cũ; thử lại sau 1 phút thay vì 5 phút.
      this.logger.warn(`Không đọc được GitHub Releases: ${err?.message ?? err}`);
      this.githubCache = { at: Date.now() - GITHUB_CACHE_MS + 60_000, value: this.githubCache.value };
    }
    return this.githubCache.value;
  }

  async updateInfo(platform: 'android' | 'ios', current: number): Promise<UpdateInfo> {
    const cfg = await this.versionConfig();
    if (platform === 'ios') {
      const p = cfg.ios;
      const latest = p.latestBuild;
      const required = p.forceLatest ? latest : p.minBuild;
      return {
        platform,
        currentVersionCode: current,
        latestVersionCode: latest,
        latestVersionName: p.latestVersion,
        requiredVersionCode: required,
        updateAvailable: latest > 0 && current > 0 && current < latest,
        forceUpdate: required > 0 && current > 0 && current < required && !!p.downloadUrl,
        downloadUrl: p.downloadUrl,
        releaseNotes: p.releaseNotes,
      };
    }
    const p = cfg.android;
    const gh = p.autoFromGithub ? await this.latestGithubRelease() : null;
    const useGithub = !!gh && gh.versionCode >= p.latestVersionCode;
    const latest = useGithub ? gh!.versionCode : p.latestVersionCode;
    const latestName = useGithub ? gh!.versionName : p.latestVersionName;
    const downloadUrl = p.downloadUrl || (useGithub ? gh!.downloadUrl : '');
    const required = p.forceLatest ? latest : p.minVersionCode;
    return {
      platform,
      currentVersionCode: current,
      latestVersionCode: latest,
      latestVersionName: latestName,
      requiredVersionCode: required,
      // current = 0/1: bản build tay trên máy dev → không nhắc/ép.
      updateAvailable: current > 1 && latest > current && !!downloadUrl,
      forceUpdate: current > 1 && required > current && !!downloadUrl,
      downloadUrl,
      releaseNotes: p.releaseNotes || (useGithub ? gh!.title : ''),
    };
  }
}
