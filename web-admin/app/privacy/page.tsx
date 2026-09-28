'use client';

import { H2, LegalPage } from '@/components/LegalPage';

export default function PrivacyPage() {
  return (
    <LegalPage title="Chính sách quyền riêng tư" updated="28/09/2026">
      {(info) => (
        <>
          <p>
            Chính sách này mô tả dữ liệu mà ứng dụng {info.appName} (Android, iOS) và website đi kèm thu thập, lý do thu thập và quyền của bạn. Nguyên tắc
            chung: tài liệu bạn quét <strong className="text-ink">ở lại trên điện thoại</strong> trừ khi bạn tự bấm sao lưu hoặc dùng dịch vụ đám mây.
          </p>

          <H2>1. Dữ liệu xử lý trên máy (không gửi đi)</H2>
          <p>Ảnh chụp tài liệu, file PDF, nhận dạng chữ (OCR), bộ lọc ảnh và xuất Word/Excel/PowerPoint được xử lý ngay trên điện thoại.</p>

          <H2>2. Thông tin thiết bị</H2>
          <p>
            Mỗi lần mở app, ứng dụng gửi về máy chủ: mã cài đặt ngẫu nhiên (tạo khi cài app, mất khi gỡ app), phiên bản ứng dụng, dòng máy, phiên bản hệ
            điều hành và ngôn ngữ. Mục đích: thống kê lượt cài, thông báo và yêu cầu cập nhật phiên bản, hỗ trợ kỹ thuật. Không thu thập danh bạ, vị trí,
            số điện thoại hay mã định danh phần cứng.
          </p>

          <H2>3. Tài khoản (tuỳ chọn)</H2>
          <p>
            Khi đăng nhập bằng Google hoặc email, chúng tôi lưu email, tên hiển thị và mã tài khoản do Firebase Authentication (Google) cấp để quản lý gói
            Business và sao lưu. Mật khẩu do Firebase quản lý, chúng tôi không đọc được. Không đăng nhập vẫn dùng được các tính năng miễn phí.
          </p>

          <H2>4. Sao lưu đám mây (tuỳ chọn)</H2>
          <p>
            Khi bạn bấm «Sao lưu lên đám mây», file PDF cùng tiêu đề, số trang và dung lượng được lưu trên dịch vụ lưu trữ Supabase (máy chủ tại châu Á),
            chỉ tài khoản của bạn và quản trị viên hệ thống truy cập được.
          </p>

          <H2>5. Dịch và AI Cloud (tuỳ chọn)</H2>
          <p>
            Khi bạn bật dịch tài liệu hoặc AI Cloud, nội dung trang được gửi thẳng từ điện thoại tới nhà cung cấp bạn chọn (Google Dịch, Google Gemini,
            Anthropic Claude) bằng API key của chính bạn, theo chính sách của nhà cung cấp đó. Nội dung này không đi qua máy chủ {info.appName}.
          </p>

          <H2>6. Thanh toán</H2>
          <p>
            Gói Business thanh toán qua cổng VNPay hoặc MoMo. Chúng tôi chỉ lưu mã đơn, gói, số tiền, trạng thái và mã giao dịch do cổng trả về; không lưu
            số thẻ hay thông tin tài khoản ngân hàng.
          </p>

          <H2>7. Chia sẻ dữ liệu</H2>
          <p>
            Không bán dữ liệu. Dữ liệu chỉ được xử lý bởi các nhà cung cấp hạ tầng nêu trên (Google Firebase, Supabase, Render, Vercel, VNPay, MoMo) để vận
            hành dịch vụ, hoặc khi pháp luật yêu cầu.
          </p>

          <H2>8. Lưu giữ và xoá dữ liệu</H2>
          <p>
            Dữ liệu tài khoản và bản sao lưu được giữ tới khi bạn yêu cầu xoá. Bạn có thể yêu cầu xem, sửa hoặc xoá tài khoản cùng toàn bộ bản sao lưu qua
            thông tin liên hệ bên dưới. Gỡ ứng dụng sẽ xoá toàn bộ tài liệu trên máy.
          </p>

          <H2>9. Trẻ em</H2>
          <p>Ứng dụng không hướng tới trẻ em dưới 13 tuổi và không cố ý thu thập dữ liệu của trẻ em.</p>

          <H2>10. Thay đổi chính sách</H2>
          <p>Khi có thay đổi quan trọng, chúng tôi cập nhật ngày ở đầu trang và thông báo trong ứng dụng.</p>
        </>
      )}
    </LegalPage>
  );
}
