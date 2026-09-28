'use client';

import { H2, LegalPage } from '@/components/LegalPage';

export default function TermsPage() {
  return (
    <LegalPage title="Điều khoản sử dụng" updated="28/09/2026">
      {(info) => (
        <>
          <p>Khi cài đặt hoặc sử dụng {info.appName}, bạn đồng ý với các điều khoản dưới đây.</p>

          <H2>1. Dịch vụ</H2>
          <p>
            {info.appName} cung cấp công cụ quét, nhận dạng chữ, chuyển đổi và dịch tài liệu. Gói Miễn phí và gói Business có phạm vi tính năng như trên trang
            Bảng giá; phạm vi này có thể được điều chỉnh và công bố trên trang Bảng giá.
          </p>

          <H2>2. Tài khoản</H2>
          <p>Bạn chịu trách nhiệm bảo mật tài khoản đăng nhập. Mỗi gói Business gắn với một tài khoản và dùng được trên các thiết bị đăng nhập tài khoản đó.</p>

          <H2>3. Thanh toán gói Business</H2>
          <p>
            Gói Business thanh toán một lần qua VNPay hoặc MoMo, không tự động gia hạn. Mua thêm khi gói còn hạn thì thời gian được cộng dồn. Trường hợp đã
            thanh toán nhưng gói chưa kích hoạt, vui lòng liên hệ kèm mã đơn để được xử lý.
          </p>

          <H2>4. Nội dung của bạn</H2>
          <p>
            Bạn giữ toàn quyền với tài liệu của mình và chịu trách nhiệm về tính hợp pháp của tài liệu được quét, dịch hoặc sao lưu. Không dùng dịch vụ để
            xử lý nội dung vi phạm pháp luật hoặc quyền của người khác.
          </p>

          <H2>5. Dịch vụ bên thứ ba</H2>
          <p>
            Tính năng dịch và AI Cloud dùng API key của chính bạn với Google hoặc Anthropic; chi phí và điều khoản của các dịch vụ đó do nhà cung cấp quy định.
          </p>

          <H2>6. Giới hạn trách nhiệm</H2>
          <p>
            Kết quả nhận dạng chữ, chuyển đổi và dịch được tạo tự động và có thể chưa chính xác tuyệt đối; bạn nên kiểm tra lại trước khi sử dụng cho mục đích
            quan trọng. Bạn nên giữ bản sao tài liệu gốc.
          </p>

          <H2>7. Cập nhật ứng dụng</H2>
          <p>Để đảm bảo an toàn và tương thích, một số phiên bản cũ có thể bị yêu cầu cập nhật bắt buộc trước khi tiếp tục sử dụng.</p>

          <H2>8. Thay đổi điều khoản</H2>
          <p>Điều khoản có thể được cập nhật; ngày cập nhật ghi ở đầu trang. Tiếp tục sử dụng sau khi cập nhật nghĩa là bạn đồng ý với điều khoản mới.</p>
        </>
      )}
    </LegalPage>
  );
}
