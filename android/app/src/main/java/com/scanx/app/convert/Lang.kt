package com.scanx.app.convert

/**
 * Ngôn ngữ / hệ chữ của từng dòng chữ (Kotlin thuần). Dùng để:
 *  - gộp kết quả nhiều bộ OCR (Latin + Hàn + Nhật + Trung) — [ScriptMerge];
 *  - gán font + mã ngôn ngữ đúng cho Word/Excel/PowerPoint (chữ Hàn/Nhật/Trung không bị ô vuông,
 *    kiểm tra chính tả đúng ngôn ngữ);
 *  - chọn ngôn ngữ nguồn khi dịch.
 * Mã ngôn ngữ theo BCP-47 rút gọn: "vi", "en", "ko", "ja", "zh", "de", "fr"…; "" = chưa rõ.
 */
object Lang {

    enum class Script { LATIN, HANGUL, KANA, HAN, OTHER }

    class ScriptCount(val latin: Int, val hangul: Int, val kana: Int, val han: Int, val other: Int) {
        val letters: Int get() = latin + hangul + kana + han + other
        val dominant: Script
            get() {
                val m = maxOf(latin, hangul, kana, han, other)
                return when {
                    m == 0 -> Script.OTHER
                    hangul == m -> Script.HANGUL
                    kana > 0 && kana + han >= m -> Script.KANA
                    han == m -> Script.HAN
                    latin == m -> Script.LATIN
                    else -> Script.OTHER
                }
            }
    }

    fun count(text: String): ScriptCount {
        var latin = 0; var hangul = 0; var kana = 0; var han = 0; var other = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F -> hangul++
                c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF -> han++
                ch.isLetter() && (c < 0x250 || c in 0x1E00..0x1EFF) -> latin++
                ch.isLetter() -> other++
            }
        }
        return ScriptCount(latin, hangul, kana, han, other)
    }

    private const val VI_MARKS = "ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ" +
        "ÀÁẢÃẠẰẮẲẴẶẦẤẨẪẬÈÉẺẼẸỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌỒỐỔỖỘỜỚỞỠỢÙÚỦŨỤỪỨỬỮỰỲÝỶỸỴ"

    /**
     * Ngôn ngữ của 1 đoạn chữ. [hint] = kết quả nhận diện ngôn ngữ (ML Kit Language ID) nếu có.
     * Hệ chữ quyết định trước (Hàn/Nhật/Trung chắc chắn), chữ Latin dùng [hint] hoặc dấu tiếng Việt.
     */
    fun detect(text: String, hint: String? = null): String {
        val c = count(text)
        if (c.letters == 0) return hint.orEmpty()
        return when (c.dominant) {
            Script.HANGUL -> "ko"
            Script.KANA -> "ja"
            Script.HAN -> if (hint == "ja") "ja" else "zh"
            Script.LATIN -> {
                val h = hint?.takeIf { it.isNotBlank() && it != "und" }
                val viMarks = text.count { it in VI_MARKS }
                when {
                    h == "vi" -> "vi"
                    viMarks >= 2 || (viMarks >= 1 && c.latin < 12) -> "vi"
                    h != null -> h
                    else -> "en"
                }
            }
            Script.OTHER -> hint.orEmpty()
        }
    }

    fun isEastAsian(lang: String) = lang == "ko" || lang == "ja" || lang == "zh"

    /** Font chuẩn cho ngôn ngữ (có sẵn trên Windows/Office và được thay tương đương trên máy khác). */
    fun fontFor(lang: String): String = when (lang) {
        "ko" -> "Malgun Gothic"
        "ja" -> "Yu Mincho"
        "zh" -> "SimSun"
        else -> DEFAULT_FONT
    }

    /** Mã ngôn ngữ đầy đủ cho thuộc tính lang của OOXML. */
    fun ooxml(lang: String): String = when (lang) {
        "vi" -> "vi-VN"
        "en" -> "en-US"
        "ko" -> "ko-KR"
        "ja" -> "ja-JP"
        "zh" -> "zh-CN"
        "de" -> "de-DE"
        "fr" -> "fr-FR"
        "es" -> "es-ES"
        "ru" -> "ru-RU"
        "th" -> "th-TH"
        "" -> "vi-VN"
        else -> lang
    }

    /** Tên hiển thị tiếng Việt. */
    fun displayName(lang: String): String = when (lang) {
        "vi" -> "Tiếng Việt"
        "en" -> "Tiếng Anh"
        "ko" -> "Tiếng Hàn"
        "ja" -> "Tiếng Nhật"
        "zh" -> "Tiếng Trung"
        "de" -> "Tiếng Đức"
        "fr" -> "Tiếng Pháp"
        "es" -> "Tiếng Tây Ban Nha"
        "ru" -> "Tiếng Nga"
        "th" -> "Tiếng Thái"
        else -> lang.ifBlank { "Không rõ" }
    }
}
