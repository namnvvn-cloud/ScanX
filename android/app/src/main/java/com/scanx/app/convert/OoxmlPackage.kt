package com.scanx.app.convert

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ghi gói Office Open XML (.docx/.xlsx/.pptx đều là file ZIP chứa các phần XML). Tự viết bằng
 * java.util.zip thay vì Apache POI: POI nặng ~15 MB, nhiều lỗi tương thích trên Android; định dạng
 * OOXML là chuẩn mở ECMA-376 nên ghi trực tiếp vừa nhẹ vừa kiểm soát được từng chi tiết bố cục.
 */
class OoxmlPackage {
    private val parts = LinkedHashMap<String, ByteArray>()

    fun put(path: String, xml: String) {
        parts[path] = xml.toByteArray(Charsets.UTF_8)
    }

    fun putBinary(path: String, bytes: ByteArray) {
        parts[path] = bytes
    }

    fun writeTo(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // [Content_Types].xml phải là phần đầu tiên để một số trình đọc cũ nhận diện đúng.
            val ordered = parts.entries.sortedBy { if (it.key == "[Content_Types].xml") 0 else 1 }
            for ((path, bytes) in ordered) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch)
        }
    }
    return sb.toString()
}

internal const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

/** Font mặc định cho văn bản tiếng Việt (chuẩn văn bản hành chính theo Nghị định 30/2020). */
internal const val DEFAULT_FONT = "Times New Roman"
