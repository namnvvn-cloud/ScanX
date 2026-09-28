package com.scanx.app.data

import java.util.Locale

/** 1 dòng trong báo cáo chi phí (sửa được trên màn hình trước khi xuất). */
data class ExpenseItem(
    val documentId: String,
    val merchant: String,
    val date: String,
    val amount: Long?,
    val category: String = "",
    val note: String = "",
)

/**
 * Đọc hoá đơn / biên lai từ chữ OCR: tên cửa hàng (dòng chữ đầu), ngày, tổng tiền. Heuristic cho hoá
 * đơn Việt Nam ("Tổng cộng", "Thành tiền", "Tổng thanh toán"…) và tiếng Anh ("Total", "Amount due").
 * Không chắc chắn → để trống cho người dùng tự điền.
 */
object ReceiptParser {
    private val TOTAL_KEYS = listOf(
        "tổng thanh toán", "tổng cộng", "tong cong", "tổng tiền", "tong tien", "thành tiền", "thanh tien",
        "tiền thanh toán", "cần thanh toán", "phải trả", "khách phải trả", "cộng tiền hàng", "total", "amount due",
        "grand total", "tổng",
    )
    private val RECEIPT_WORDS = listOf(
        "hoá đơn", "hóa đơn", "biên lai", "phiếu thu", "tổng cộng", "thành tiền", "tổng tiền", "thanh toán",
        "receipt", "invoice", "total", "vat", "mst", "tiền mặt", "đơn giá",
    )
    private val MONEY = Regex("""(\d{1,3}(?:[.,\s]\d{3})+|\d+(?:[.,]\d{1,2})?)\s*(?:đ|₫|vnd|vnđ|dong|đồng)?""", RegexOption.IGNORE_CASE)
    private val DATE_DMY = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})\b""")
    private val DATE_YMD = Regex("""\b(\d{4})[/.\-](\d{1,2})[/.\-](\d{1,2})\b""")

    fun looksLikeReceipt(text: String): Boolean {
        val t = text.lowercase(Locale("vi"))
        return RECEIPT_WORDS.count { t.contains(it) } >= 2
    }

    fun parse(documentId: String, title: String, text: String): ExpenseItem {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("--- Trang") }
        return ExpenseItem(
            documentId = documentId,
            merchant = merchant(lines).ifBlank { title },
            date = date(text),
            amount = total(lines),
        )
    }

    private fun merchant(lines: List<String>): String =
        lines.take(6).firstOrNull { l ->
            val low = l.lowercase(Locale("vi"))
            l.count { it.isLetter() } >= 3 && RECEIPT_WORDS.none { low.contains(it) } && !low.startsWith("đc") && !low.startsWith("địa chỉ")
        }?.take(60).orEmpty()

    private fun date(text: String): String {
        DATE_DMY.find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            val dd = d.toInt()
            val mm = mo.toInt()
            if (dd in 1..31 && mm in 1..12) {
                val yyyy = if (y.length == 2) "20$y" else y
                return String.format(Locale.US, "%02d/%02d/%s", dd, mm, yyyy)
            }
        }
        DATE_YMD.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            if (d.toInt() in 1..31 && mo.toInt() in 1..12) return String.format(Locale.US, "%02d/%02d/%s", d.toInt(), mo.toInt(), y)
        }
        return ""
    }

    private fun total(lines: List<String>): Long? {
        // Ưu tiên dòng có từ khoá tổng (dò từ dưới lên — tổng thường ở cuối), lấy số lớn nhất trên dòng
        // đó hoặc dòng ngay sau.
        for (key in TOTAL_KEYS) {
            for (i in lines.indices.reversed()) {
                if (!lines[i].lowercase(Locale("vi")).contains(key)) continue
                val v = maxAmount(lines[i]) ?: lines.getOrNull(i + 1)?.let { maxAmount(it) }
                if (v != null && v > 0) return v
            }
        }
        // Không có từ khoá: số tiền lớn nhất có dấu phân cách nghìn.
        return lines.mapNotNull { l -> MONEY.findAll(l).mapNotNull { parseAmount(it.groupValues[1], requireThousands = true) }.maxOrNull() }.maxOrNull()
    }

    private fun maxAmount(line: String): Long? =
        MONEY.findAll(line).mapNotNull { parseAmount(it.groupValues[1], requireThousands = false) }.filter { it >= 1000 }.maxOrNull()

    /** "1.250.000" / "1,250,000" / "1 250 000" → 1250000; "12.50" → 13 (làm tròn); năm/ngày lẻ bị loại. */
    fun parseAmount(raw: String, requireThousands: Boolean): Long? {
        val s = raw.trim()
        if (Regex("""^\d{1,3}([.,\s]\d{3})+$""").matches(s)) return s.replace(Regex("[.,\\s]"), "").toLongOrNull()
        if (requireThousands) return null
        if (Regex("""^\d+[.,]\d{1,2}$""").matches(s)) return s.replace(',', '.').toDoubleOrNull()?.let { Math.round(it) }
        return s.toLongOrNull()?.takeIf { it < 10_000_000_000L }
    }
}
