'use client';

import { useEffect, useRef, useState } from 'react';

interface Point {
  day: string; // YYYY-MM-DD
  value: number;
}

/**
 * Biểu đồ cột theo ngày (1 chuỗi → không cần chú giải; tiêu đề nêu tên chuỗi).
 * Cột mảnh ≤ 24px, bo 4px đầu cột, neo đáy; trục/lưới mờ; hover hiện tooltip; có nút xem dạng bảng.
 */
export function DailyBars({ title, data, format }: { title: string; data: Point[]; format: (n: number) => string }) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(260, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const height = 180;
  const pad = { top: 12, right: 8, bottom: 24, left: 48 };
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  const max = niceMax(Math.max(0, ...data.map((d) => d.value)));
  const step = plotW / Math.max(1, data.length);
  const barW = Math.min(24, Math.max(3, step - 2)); // khe 2px giữa các cột
  const y = (v: number) => pad.top + plotH - (v / max) * plotH;
  const ticks = [0, max / 2, max];
  const total = data.reduce((s, d) => s + d.value, 0);
  const hp = hover !== null ? data[hover] : null;

  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-medium text-ink-2">{title}</h3>
          <p className="tabular mt-1 text-xl font-semibold">{format(total)}</p>
          <p className="text-xs text-ink-3">30 ngày gần nhất</p>
        </div>
        <button onClick={() => setAsTable((v) => !v)} className="rounded-md px-2 py-1 text-xs text-ink-2 hover:bg-surface-2">
          {asTable ? 'Xem biểu đồ' : 'Xem bảng'}
        </button>
      </div>

      {asTable ? (
        <div className="mt-3 max-h-56 overflow-y-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-surface text-left text-xs text-ink-3">
              <tr>
                <th className="py-1 font-medium">Ngày</th>
                <th className="py-1 text-right font-medium">{title}</th>
              </tr>
            </thead>
            <tbody>
              {data.map((d) => (
                <tr key={d.day} className="border-t border-line">
                  <td className="py-1">{dayLabel(d.day)}</td>
                  <td className="tabular py-1 text-right">{format(d.value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={wrap} className="relative mt-3" onMouseLeave={() => setHover(null)}>
          <svg width={width} height={height} role="img" aria-label={`${title} theo ngày, tổng ${format(total)}`} className="block">
            {ticks.map((t) => (
              <g key={t}>
                <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} stroke="var(--line)" strokeWidth={1} />
                <text x={pad.left - 6} y={y(t)} dy="0.32em" textAnchor="end" fontSize={10} fill="var(--ink-3)" className="tabular">
                  {compact(t)}
                </text>
              </g>
            ))}
            {data.map((d, i) => {
              const cx = pad.left + step * i + step / 2;
              const h = Math.max(0, y(0) - y(d.value));
              return (
                <g key={d.day}>
                  {h > 0 && (
                    <path
                      d={roundedTopBar(cx - barW / 2, y(0), barW, h, Math.min(4, barW / 2, h))}
                      fill="var(--accent)"
                      opacity={hover === null || hover === i ? 1 : 0.45}
                    />
                  )}
                  {/* vùng hover rộng hơn cột */}
                  <rect x={pad.left + step * i} y={pad.top} width={step} height={plotH} fill="transparent" onMouseEnter={() => setHover(i)} />
                </g>
              );
            })}
            {[0, Math.floor(data.length / 2), data.length - 1].filter((i) => data[i]).map((i) => (
              <text
                key={i}
                x={pad.left + step * i + step / 2}
                y={height - 6}
                textAnchor="middle"
                fontSize={10}
                fill="var(--ink-3)"
              >
                {dayLabel(data[i].day)}
              </text>
            ))}
          </svg>
          {hp && hover !== null && (
            <div
              className="pointer-events-none absolute -translate-x-1/2 rounded-md border border-line bg-surface px-2.5 py-1.5 text-xs shadow-sm"
              style={{ left: Math.min(width - 60, Math.max(60, pad.left + step * hover + step / 2)), top: 0 }}
            >
              <div className="text-ink-3">{dayLabel(hp.day)}</div>
              <div className="tabular font-semibold text-ink">{format(hp.value)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function roundedTopBar(x: number, base: number, w: number, h: number, r: number): string {
  const top = base - h;
  return `M${x},${base} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${base} Z`;
}

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}

function compact(n: number): string {
  return new Intl.NumberFormat('vi-VN', { notation: 'compact', maximumFractionDigits: 1 }).format(n);
}

function dayLabel(day: string): string {
  const [, m, d] = day.split('-');
  return `${d}/${m}`;
}
