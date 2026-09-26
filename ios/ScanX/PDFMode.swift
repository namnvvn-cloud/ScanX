import Foundation

/// 4 chế độ PDF — cùng mã và ý nghĩa với Android (ScanPdfWriter / meta.pdfMode).
enum PDFMode: String, CaseIterable, Identifiable, Codable {
    case a1 = "A1"
    case a2 = "A2"
    case b1 = "B1"
    case b2 = "B2"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .a1: return "A1 – Đen trắng nhỏ gọn"
        case .a2: return "A2 – Đen trắng chất lượng cao"
        case .b1: return "B1 – Màu nhỏ gọn"
        case .b2: return "B2 – Màu chất lượng cao"
        }
    }

    var detail: String {
        switch self {
        case .a1: return "1-bit, ~30–60 KB/trang"
        case .a2: return "Xám JPEG, ~150–340 KB/trang"
        case .b1: return "Màu JPEG 1600 px, ~130–220 KB/trang"
        case .b2: return "Màu JPEG chất lượng cao, ~300–450 KB/trang"
        }
    }
}

enum AppSettings {
    static let pdfModeKey = "pdf_mode"
    static let ocrEnabledKey = "ocr_enabled"

    static var pdfMode: PDFMode {
        PDFMode(rawValue: UserDefaults.standard.string(forKey: pdfModeKey) ?? "") ?? .a2
    }

    static var ocrEnabled: Bool {
        UserDefaults.standard.object(forKey: ocrEnabledKey) as? Bool ?? true
    }
}
