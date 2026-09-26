import CoreGraphics
import Foundation

/// 1 nét bút xoá: điểm chuẩn hoá 0…1 (gốc trên-trái), bán kính chuẩn hoá theo bề rộng ảnh.
struct CleanupStroke: Equatable {
    var points: [CGPoint]
    var radius: CGFloat
}

/// Làm sạch vết bẩn (tương đương scan/PageCleanup.kt). Android dùng OpenCV inpaint Telea;
/// iOS không có OpenCV nên dùng vá "bóc vỏ hành": điền dần từ mép vùng tô vào trong bằng trung bình
/// các điểm đã biết xung quanh, rồi làm mịn vài lượt — hợp với vết bẩn nhỏ trên nền giấy.
enum PageCleanup {
    static func heal(_ image: ScanFilters.RGBX, strokes: [CleanupStroke]) -> ScanFilters.RGBX {
        let w = image.width
        let h = image.height
        guard w > 2, h > 2, !strokes.isEmpty else { return image }

        var mask = [Bool](repeating: false, count: w * h)
        var minX = w, minY = h, maxX = -1, maxY = -1

        func stamp(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            let ri = Int(r.rounded(.up))
            let icx = Int(cx.rounded())
            let icy = Int(cy.rounded())
            let y0 = max(icy - ri, 0)
            let y1 = min(icy + ri, h - 1)
            let x0 = max(icx - ri, 0)
            let x1 = min(icx + ri, w - 1)
            guard y0 <= y1, x0 <= x1 else { return }
            let r2 = r * r
            for y in y0...y1 {
                let dy = CGFloat(y - icy)
                for x in x0...x1 {
                    let dx = CGFloat(x - icx)
                    if dx * dx + dy * dy <= r2 {
                        mask[y * w + x] = true
                    }
                }
            }
            minX = min(minX, x0)
            maxX = max(maxX, x1)
            minY = min(minY, y0)
            maxY = max(maxY, y1)
        }

        for stroke in strokes {
            // +2 px ≈ dilate mask như bản Android, để vá phủ hết viền vết bẩn.
            let r = max(stroke.radius * CGFloat(w), 1) + 2
            let pts = stroke.points.map { CGPoint(x: $0.x * CGFloat(w), y: $0.y * CGFloat(h)) }
            guard let first = pts.first else { continue }
            stamp(first.x, first.y, r)
            if pts.count > 1 {
                for i in 1..<pts.count {
                    let a = pts[i - 1]
                    let b = pts[i]
                    let dist = hypot(b.x - a.x, b.y - a.y)
                    let steps = max(1, Int(dist / max(r * 0.5, 1)))
                    for s in 1...steps {
                        let t = CGFloat(s) / CGFloat(steps)
                        stamp(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, r)
                    }
                }
            }
        }
        guard maxX >= minX, maxY >= minY else { return image }

        var px = image.pixels
        var known = mask.map { !$0 }
        var remaining = mask.reduce(0) { $0 + ($1 ? 1 : 0) }
        var passes = 0
        while remaining > 0, passes < 4000 {
            var updates: [(Int, UInt8, UInt8, UInt8)] = []
            for y in minY...maxY {
                for x in minX...maxX {
                    let i = y * w + x
                    if known[i] { continue }
                    var sr = 0, sg = 0, sb = 0, n = 0
                    for dy in -1...1 {
                        let ny = y + dy
                        if ny < 0 || ny >= h { continue }
                        for dx in -1...1 {
                            let nx = x + dx
                            if nx < 0 || nx >= w || (dx == 0 && dy == 0) { continue }
                            let j = ny * w + nx
                            if known[j] {
                                sr += Int(px[j * 4])
                                sg += Int(px[j * 4 + 1])
                                sb += Int(px[j * 4 + 2])
                                n += 1
                            }
                        }
                    }
                    if n > 0 {
                        updates.append((i, UInt8(sr / n), UInt8(sg / n), UInt8(sb / n)))
                    }
                }
            }
            if updates.isEmpty { break }
            for (i, r, g, b) in updates {
                px[i * 4] = r
                px[i * 4 + 1] = g
                px[i * 4 + 2] = b
                known[i] = true
            }
            remaining -= updates.count
            passes += 1
        }

        // Làm mịn vùng vá (trung bình 4 lân cận, 4 lượt) để bớt vệt.
        for _ in 0..<4 {
            let snapshot = px
            for y in max(minY, 1)...max(min(maxY, h - 2), max(minY, 1)) {
                for x in max(minX, 1)...max(min(maxX, w - 2), max(minX, 1)) {
                    let i = y * w + x
                    guard mask[i], x < w - 1, y < h - 1 else { continue }
                    for c in 0..<3 {
                        let sum = Int(snapshot[(i - 1) * 4 + c]) + Int(snapshot[(i + 1) * 4 + c])
                            + Int(snapshot[(i - w) * 4 + c]) + Int(snapshot[(i + w) * 4 + c])
                        px[i * 4 + c] = UInt8((Int(snapshot[i * 4 + c]) + sum / 4) / 2)
                    }
                }
            }
        }
        return ScanFilters.RGBX(width: w, height: h, pixels: px)
    }
}
