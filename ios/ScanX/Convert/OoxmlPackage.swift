import Foundation

/// Gói Office Open XML (.docx/.xlsx/.pptx là file ZIP chứa các phần XML). Android dùng java.util.zip;
/// iOS không có API ZIP công khai nên tự ghi ZIP dạng STORED (không nén — hợp lệ theo chuẩn, Office/
/// LibreOffice/WPS đều đọc được; ảnh bên trong vốn đã là JPEG nén sẵn).
final class OoxmlPackage {
    private var parts: [(path: String, data: Data)] = []

    func put(_ path: String, _ xml: String) {
        putBinary(path, Data(xml.utf8))
    }

    func putBinary(_ path: String, _ data: Data) {
        if let index = parts.firstIndex(where: { $0.path == path }) {
            parts[index].data = data
        } else {
            parts.append((path, data))
        }
    }

    func archive() -> Data {
        // [Content_Types].xml phải đứng đầu để một số trình đọc cũ nhận diện đúng.
        let ordered = parts.filter { $0.path == "[Content_Types].xml" } + parts.filter { $0.path != "[Content_Types].xml" }
        return ZipWriter.storedArchive(ordered)
    }
}

enum ZipWriter {
    private static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 {
            c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for byte in raw {
                crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
            }
        }
        return crc ^ 0xFFFF_FFFF
    }

    static func storedArchive(_ entries: [(path: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()
        // Ngày giờ DOS cố định 1/1/2026 00:00.
        let dosTime: UInt16 = 0
        let dosDate: UInt16 = UInt16(((2026 - 1980) << 9) | (1 << 5) | 1)
        for entry in entries {
            let name = Data(entry.path.utf8)
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)
            let offset = UInt32(out.count)

            out.appendLE(UInt32(0x0403_4B50))
            out.appendLE(UInt16(20))        // version needed
            out.appendLE(UInt16(0x0800))    // UTF-8 file names
            out.appendLE(UInt16(0))         // stored
            out.appendLE(dosTime)
            out.appendLE(dosDate)
            out.appendLE(crc)
            out.appendLE(size)
            out.appendLE(size)
            out.appendLE(UInt16(name.count))
            out.appendLE(UInt16(0))
            out.append(name)
            out.append(entry.data)

            central.appendLE(UInt32(0x0201_4B50))
            central.appendLE(UInt16(20))    // version made by
            central.appendLE(UInt16(20))    // version needed
            central.appendLE(UInt16(0x0800))
            central.appendLE(UInt16(0))
            central.appendLE(dosTime)
            central.appendLE(dosDate)
            central.appendLE(crc)
            central.appendLE(size)
            central.appendLE(size)
            central.appendLE(UInt16(name.count))
            central.appendLE(UInt16(0))     // extra
            central.appendLE(UInt16(0))     // comment
            central.appendLE(UInt16(0))     // disk
            central.appendLE(UInt16(0))     // internal attrs
            central.appendLE(UInt32(0))     // external attrs
            central.appendLE(offset)
            central.append(name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.appendLE(UInt32(0x0605_4B50))
        out.appendLE(UInt16(0))
        out.appendLE(UInt16(0))
        out.appendLE(UInt16(entries.count))
        out.appendLE(UInt16(entries.count))
        out.appendLE(UInt32(central.count))
        out.appendLE(centralOffset)
        out.appendLE(UInt16(0))
        return out
    }
}

extension Data {
    mutating func appendLE(_ value: UInt16) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    mutating func appendLE(_ value: UInt32) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}
