import Foundation
import UIKit

/// 1 dòng báo cáo chi phí (sửa được trước khi xuất).
struct ExpenseItem: Identifiable, Hashable {
    var id: String { documentID }
    let documentID: String
    var include = true
    var merchant: String
    var date: String
    var amount: Int64?
    var category = ""
    var note = ""
}

/// Đọc hoá đơn / biên lai từ chữ OCR — cùng heuristic với ReceiptParser.kt (Android).
enum ReceiptParser {
    private static let totalKeys = [
        "tổng thanh toán", "tổng cộng", "tong cong", "tổng tiền", "tong tien", "thành tiền", "thanh tien",
        "tiền thanh toán", "cần thanh toán", "phải trả", "khách phải trả", "cộng tiền hàng", "total", "amount due",
        "grand total", "tổng",
    ]
    private static let receiptWords = [
        "hoá đơn", "hóa đơn", "biên lai", "phiếu thu", "tổng cộng", "thành tiền", "tổng tiền", "thanh toán",
        "receipt", "invoice", "total", "vat", "mst", "tiền mặt", "đơn giá",
    ]
    private static let money = try! NSRegularExpression(
        pattern: #"(\d{1,3}(?:[.,\s]\d{3})+|\d+(?:[.,]\d{1,2})?)\s*(?:đ|₫|vnd|vnđ|dong|đồng)?"#, options: [.caseInsensitive])
    private static let dateDMY = try! NSRegularExpression(pattern: #"\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})\b"#)
    private static let dateYMD = try! NSRegularExpression(pattern: #"\b(\d{4})[/.\-](\d{1,2})[/.\-](\d{1,2})\b"#)

    static func looksLikeReceipt(_ text: String) -> Bool {
        let t = text.lowercased()
        return receiptWords.filter { t.contains($0) }.count >= 2
    }

    static func parse(documentID: String, title: String, text: String) -> ExpenseItem {
        let lines = text.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let m = merchant(lines)
        return ExpenseItem(documentID: documentID, merchant: m.isEmpty ? title : m, date: date(text), amount: total(lines))
    }

    private static func merchant(_ lines: [String]) -> String {
        for l in lines.prefix(6) {
            let low = l.lowercased()
            if l.filter({ $0.isLetter }).count >= 3, !receiptWords.contains(where: { low.contains($0) }),
               !low.hasPrefix("đc"), !low.hasPrefix("địa chỉ") {
                return String(l.prefix(60))
            }
        }
        return ""
    }

    private static func groups(_ re: NSRegularExpression, _ s: String) -> [String]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (1..<m.numberOfRanges).map { ns.substring(with: m.range(at: $0)) }
    }

    private static func date(_ text: String) -> String {
        if let g = groups(dateDMY, text), let d = Int(g[0]), let mo = Int(g[1]), (1...31).contains(d), (1...12).contains(mo) {
            let y = g[2].count == 2 ? "20" + g[2] : g[2]
            return String(format: "%02d/%02d/%@", d, mo, y)
        }
        if let g = groups(dateYMD, text), let mo = Int(g[1]), let d = Int(g[2]), (1...31).contains(d), (1...12).contains(mo) {
            return String(format: "%02d/%02d/%@", d, mo, g[0])
        }
        return ""
    }

    private static func amounts(_ line: String, requireThousands: Bool) -> [Int64] {
        let ns = line as NSString
        return money.matches(in: line, range: NSRange(location: 0, length: ns.length)).compactMap {
            parseAmount(ns.substring(with: $0.range(at: 1)), requireThousands: requireThousands)
        }
    }

    private static func total(_ lines: [String]) -> Int64? {
        for key in totalKeys {
            for i in lines.indices.reversed() where lines[i].lowercased().contains(key) {
                let onLine = amounts(lines[i], requireThousands: false).filter { $0 >= 1000 }.max()
                let next = i + 1 < lines.count ? amounts(lines[i + 1], requireThousands: false).filter { $0 >= 1000 }.max() : nil
                if let v = onLine ?? next, v > 0 { return v }
            }
        }
        return lines.compactMap { amounts($0, requireThousands: true).max() }.max()
    }

    /// "1.250.000" / "1,250,000" / "1 250 000" → 1250000; "12.50" → 13.
    static func parseAmount(_ raw: String, requireThousands: Bool) -> Int64? {
        let s = raw.trimmingCharacters(in: .whitespaces)
        if s.range(of: #"^\d{1,3}([.,\s]\d{3})+$"#, options: .regularExpression) != nil {
            return Int64(s.filter { $0.isNumber })
        }
        if requireThousands { return nil }
        if s.range(of: #"^\d+[.,]\d{1,2}$"#, options: .regularExpression) != nil {
            return Double(s.replacingOccurrences(of: ",", with: ".")).map { Int64($0.rounded()) }
        }
        guard let v = Int64(s), v < 10_000_000_000 else { return nil }
        return v
    }

    /// CSV UTF-8 có BOM (Excel mở đúng tiếng Việt).
    static func csv(_ items: [ExpenseItem], titles: [String: String]) -> String {
        func q(_ v: String) -> String { "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var out = "\u{FEFF}STT,Ngày,Đơn vị bán,Số tiền (VND),Nhóm,Ghi chú,Tài liệu\r\n"
        for (i, it) in items.enumerated() {
            out += [String(i + 1), q(it.date), q(it.merchant), String(it.amount ?? 0), q(it.category), q(it.note), q(titles[it.documentID] ?? "")]
                .joined(separator: ",") + "\r\n"
        }
        out += ",,\(q("TỔNG CỘNG")),\(items.reduce(Int64(0)) { $0 + ($1.amount ?? 0) }),,,\r\n"
        return out
    }
}

/// Chế độ "Sách": ảnh 2 trang mở (nằm ngang) → tách trái/phải theo gáy (cột tối nhất trong 40–60 %).
enum BookSplitter {
    static func split(_ image: UIImage) -> [UIImage] {
        guard let cg = image.cgImage else { return [image] }
        let w = cg.width, h = cg.height
        guard Double(w) >= Double(h) * 1.15 else { return [image] }
        let sw = 400
        let sh = max(1, Int(Double(sw) * Double(h) / Double(w)))
        var gray = [UInt8](repeating: 0, count: sw * sh)
        let ok: Bool = gray.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: sw, height: sh, bitsPerComponent: 8, bytesPerRow: sw,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: sw, height: sh))
            return true
        }
        guard ok else { return [image] }
        var means = [Double](repeating: 255, count: sw)
        let from = Int(Double(sw) * 0.40), to = Int(Double(sw) * 0.60)
        for x in max(0, from - 1)...min(sw - 1, to + 1) {
            var sum = 0
            for y in 0..<sh { sum += Int(gray[y * sw + x]) }
            means[x] = Double(sum) / Double(sh)
        }
        var bestX = sw / 2
        var best = Double.greatestFiniteMagnitude
        for x in from..<to {
            let m = (means[max(0, x - 1)] + means[x] + means[min(sw - 1, x + 1)]) / 3
            if m < best { best = m; bestX = x }
        }
        let cut = min(max(Int(Double(bestX) / Double(sw) * Double(w)), w / 3), w * 2 / 3)
        guard let left = cg.cropping(to: CGRect(x: 0, y: 0, width: cut, height: h)),
              let right = cg.cropping(to: CGRect(x: cut, y: 0, width: w - cut, height: h)) else { return [image] }
        return [UIImage(cgImage: left), UIImage(cgImage: right)]
    }
}
