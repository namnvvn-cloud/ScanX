'use client';

import { useEffect, useState } from 'react';
import { Badge, Button, Card, ErrorBox, Field, Spinner, inputClass } from '@/components/ui';
import { api, dateTime } from '@/lib/api';
import { useApi } from '@/lib/useApi';

interface AndroidPolicy {
  autoFromGithub: boolean;
  forceLatest: boolean;
  minVersionCode: number;
  latestVersionCode: number;
  latestVersionName: string;
  downloadUrl: string;
  releaseNotes: string;
}
interface IosPolicy {
  forceLatest: boolean;
  minBuild: number;
  latestBuild: number;
  latestVersion: string;
  downloadUrl: string;
  releaseNotes: string;
}
interface ProductInfo {
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
interface Config {
  appVersion: { android: AndroidPolicy; ios: IosPolicy };
  productInfo: ProductInfo;
  github: { versionCode: number; versionName: string; downloadUrl: string; title: string; publishedAt: string } | null;
}

function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <label className="flex items-start gap-2 text-sm">
      <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--accent)]" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>
        <span className="font-medium">{label}</span>
        {hint && <span className="block text-xs text-ink-3">{hint}</span>}
      </span>
    </label>
  );
}

function SaveBar({ busy, msg, onSave }: { busy: boolean; msg: { ok: boolean; text: string } | null; onSave: () => void }) {
  return (
    <div className="mt-4 flex items-center gap-3">
      <Button disabled={busy} onClick={onSave}>
        Lưu
      </Button>
      {msg && <span className={`text-sm ${msg.ok ? 'text-good' : 'text-bad'}`}>{msg.ok ? `✓ ${msg.text}` : msg.text}</span>}
    </div>
  );
}

const numInput = (v: string) => Math.max(0, Math.floor(Number(v.replace(/\D/g, '')) || 0));

export default function AppConfigPage() {
  const { data, error, loading, reload } = useApi<Config>('/admin/app-config');
  const [android, setAndroid] = useState<AndroidPolicy | null>(null);
  const [ios, setIos] = useState<IosPolicy | null>(null);
  const [info, setInfo] = useState<ProductInfo | null>(null);
  const [featuresText, setFeaturesText] = useState('');
  const [busy, setBusy] = useState<'version' | 'info' | null>(null);
  const [vMsg, setVMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [iMsg, setIMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (!data) return;
    setAndroid(data.appVersion.android);
    setIos(data.appVersion.ios);
    setInfo(data.productInfo);
    setFeaturesText((data.productInfo.features ?? []).join('\n'));
  }, [data]);

  async function saveVersion() {
    if (!android || !ios) return;
    setBusy('version');
    setVMsg(null);
    try {
      await api('/admin/app-config/app_version', { method: 'PUT', body: JSON.stringify({ android, ios }) });
      setVMsg({ ok: true, text: 'Đã lưu — app áp dụng ở lần mở / quay lại tiếp theo' });
      reload();
    } catch (e: any) {
      setVMsg({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
  }

  async function saveInfo() {
    if (!info) return;
    setBusy('info');
    setIMsg(null);
    try {
      const features = featuresText.split('\n').map((s) => s.trim()).filter(Boolean);
      await api('/admin/app-config/product_info', { method: 'PUT', body: JSON.stringify({ ...info, features }) });
      setIMsg({ ok: true, text: 'Đã lưu' });
      reload();
    } catch (e: any) {
      setIMsg({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
  }

  if (loading && !data) return <Spinner />;
  if (error) return <ErrorBox message={error.message} />;
  if (!android || !ios || !info || !data) return null;

  const gh = data.github;
  const effectiveLatest = Math.max(android.autoFromGithub && gh ? gh.versionCode : 0, android.latestVersionCode);
  const required = android.forceLatest ? effectiveLatest : android.minVersionCode;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Ứng dụng</h1>
        <p className="text-sm text-ink-2">Cập nhật phiên bản & thông tin sản phẩm hiển thị trong app — sửa ở đây, không cần phát hành lại app.</p>
      </div>

      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-semibold">Android — cập nhật phiên bản</h2>
          {gh ? (
            <Badge tone="accent">GitHub mới nhất: build {gh.versionCode} · {dateTime(gh.publishedAt)}</Badge>
          ) : (
            <Badge tone="warn">Chưa đọc được GitHub Releases</Badge>
          )}
        </div>
        <div className="mt-4 space-y-3">
          <Toggle
            checked={android.autoFromGithub}
            onChange={(v) => setAndroid({ ...android, autoFromGithub: v })}
            label="Tự lấy bản mới nhất từ GitHub Releases"
            hint="Mỗi lần CI build APK mới (tag debug-build-N) → app tự biết có bản N. Tắt khi chuyển sang phát hành qua Google Play."
          />
          <Toggle
            checked={android.forceLatest}
            onChange={(v) => setAndroid({ ...android, forceLatest: v })}
            label="Ép cập nhật lên bản mới nhất"
            hint="Bật: mọi máy dùng bản cũ hơn bản mới nhất bị chặn tới khi cập nhật. Tắt: chỉ nhắc, và chỉ ép khi thấp hơn «Bản tối thiểu»."
          />
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <Field label="Bản tối thiểu (versionCode)" hint="Dùng khi KHÔNG ép bản mới nhất">
            <input className={`${inputClass} tabular`} inputMode="numeric" value={android.minVersionCode} onChange={(e) => setAndroid({ ...android, minVersionCode: numInput(e.target.value) })} />
          </Field>
          <Field label="Bản mới nhất nhập tay" hint="0 = theo GitHub">
            <input className={`${inputClass} tabular`} inputMode="numeric" value={android.latestVersionCode} onChange={(e) => setAndroid({ ...android, latestVersionCode: numInput(e.target.value) })} />
          </Field>
          <Field label="Tên bản nhập tay">
            <input className={inputClass} value={android.latestVersionName} onChange={(e) => setAndroid({ ...android, latestVersionName: e.target.value })} />
          </Field>
          <div className="sm:col-span-3">
            <Field label="Link tải (để trống = file APK trên GitHub)" hint="Khi lên Google Play: điền https://play.google.com/store/apps/details?id=com.scanx.app">
              <input className={inputClass} value={android.downloadUrl} onChange={(e) => setAndroid({ ...android, downloadUrl: e.target.value })} />
            </Field>
          </div>
          <div className="sm:col-span-3">
            <Field label="Ghi chú phiên bản (hiện trong hộp thoại cập nhật)">
              <textarea className={inputClass} rows={2} value={android.releaseNotes} onChange={(e) => setAndroid({ ...android, releaseNotes: e.target.value })} />
            </Field>
          </div>
        </div>
        <p className="mt-3 rounded-lg bg-surface-2 px-3 py-2 text-sm text-ink-2">
          Hiệu lực: bản mới nhất = <strong className="tabular text-ink">{effectiveLatest || '—'}</strong>; máy dưới bản{' '}
          <strong className="tabular text-ink">{required || '—'}</strong> {android.forceLatest ? 'bị ÉP cập nhật' : 'bị ép, còn lại chỉ được nhắc'}.
        </p>

        <h2 className="mt-8 font-semibold">iOS — cập nhật phiên bản</h2>
        <p className="text-xs text-ink-3">Chưa phát hành iOS (chờ Apple Developer Program) — để 0 là tắt kiểm tra.</p>
        <div className="mt-3 space-y-3">
          <Toggle checked={ios.forceLatest} onChange={(v) => setIos({ ...ios, forceLatest: v })} label="Ép cập nhật lên bản mới nhất" />
        </div>
        <div className="mt-3 grid gap-3 sm:grid-cols-3">
          <Field label="Build tối thiểu">
            <input className={`${inputClass} tabular`} inputMode="numeric" value={ios.minBuild} onChange={(e) => setIos({ ...ios, minBuild: numInput(e.target.value) })} />
          </Field>
          <Field label="Build mới nhất">
            <input className={`${inputClass} tabular`} inputMode="numeric" value={ios.latestBuild} onChange={(e) => setIos({ ...ios, latestBuild: numInput(e.target.value) })} />
          </Field>
          <Field label="Tên bản">
            <input className={inputClass} value={ios.latestVersion} onChange={(e) => setIos({ ...ios, latestVersion: e.target.value })} />
          </Field>
          <div className="sm:col-span-3">
            <Field label="Link App Store / TestFlight">
              <input className={inputClass} value={ios.downloadUrl} onChange={(e) => setIos({ ...ios, downloadUrl: e.target.value })} />
            </Field>
          </div>
          <div className="sm:col-span-3">
            <Field label="Ghi chú phiên bản">
              <textarea className={inputClass} rows={2} value={ios.releaseNotes} onChange={(e) => setIos({ ...ios, releaseNotes: e.target.value })} />
            </Field>
          </div>
        </div>
        <SaveBar busy={busy === 'version'} msg={vMsg} onSave={saveVersion} />
      </Card>

      <Card>
        <h2 className="font-semibold">Thông tin sản phẩm</h2>
        <p className="text-xs text-ink-3">Hiện ở app: Cài đặt → Thông tin sản phẩm. Ô để trống sẽ không hiển thị.</p>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <Field label="Tên ứng dụng">
            <input className={inputClass} value={info.appName} onChange={(e) => setInfo({ ...info, appName: e.target.value })} />
          </Field>
          <Field label="Khẩu hiệu">
            <input className={inputClass} value={info.tagline} onChange={(e) => setInfo({ ...info, tagline: e.target.value })} />
          </Field>
          <div className="sm:col-span-2">
            <Field label="Giới thiệu">
              <textarea className={inputClass} rows={3} value={info.description} onChange={(e) => setInfo({ ...info, description: e.target.value })} />
            </Field>
          </div>
          <div className="sm:col-span-2">
            <Field label="Chức năng chính" hint="Mỗi dòng một chức năng">
              <textarea className={inputClass} rows={8} value={featuresText} onChange={(e) => setFeaturesText(e.target.value)} />
            </Field>
          </div>
          <Field label="Nhà phát hành">
            <input className={inputClass} value={info.publisher} onChange={(e) => setInfo({ ...info, publisher: e.target.value })} />
          </Field>
          <Field label="Website">
            <input className={inputClass} value={info.website} onChange={(e) => setInfo({ ...info, website: e.target.value })} />
          </Field>
          <Field label="Email hỗ trợ">
            <input className={inputClass} type="email" value={info.email} onChange={(e) => setInfo({ ...info, email: e.target.value })} />
          </Field>
          <Field label="Điện thoại">
            <input className={inputClass} value={info.phone} onChange={(e) => setInfo({ ...info, phone: e.target.value })} />
          </Field>
          <div className="sm:col-span-2">
            <Field label="Địa chỉ">
              <input className={inputClass} value={info.address} onChange={(e) => setInfo({ ...info, address: e.target.value })} />
            </Field>
          </div>
          <Field label="Link chính sách quyền riêng tư" hint="Để trống = dùng trang /privacy có sẵn trên web">
            <input className={inputClass} value={info.privacyUrl} onChange={(e) => setInfo({ ...info, privacyUrl: e.target.value })} />
          </Field>
          <Field label="Link điều khoản sử dụng" hint="Để trống = dùng trang /terms có sẵn trên web">
            <input className={inputClass} value={info.termsUrl} onChange={(e) => setInfo({ ...info, termsUrl: e.target.value })} />
          </Field>
        </div>
        <SaveBar busy={busy === 'info'} msg={iMsg} onSave={saveInfo} />
      </Card>
    </div>
  );
}
