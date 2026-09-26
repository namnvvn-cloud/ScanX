import Foundation

/// Bộ lọc riêng từng trang — cùng mã với Android (data/PageFilter.kt). Lưu ở meta.pageFilters,
/// không nướng vào ảnh gốc; nil = trang theo chế độ PDF của tài liệu.
enum PageFilter: String, CaseIterable, Identifiable, Codable {
    case original = "ORIGINAL"
    case auto = "AUTO"
    case color = "COLOR"
    case bw = "BW"
    case gray = "GRAY"
    case shadow = "SHADOW"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original: return "Ban đầu"
        case .auto: return "Tự động"
        case .color: return "Màu"
        case .bw: return "Đen trắng"
        case .gray: return "Xám"
        case .shadow: return "Bóng"
        }
    }
}
