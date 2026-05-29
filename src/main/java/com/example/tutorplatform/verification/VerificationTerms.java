package com.example.tutorplatform.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class VerificationTerms {
  public static final String VERSION = "tutor-verification-v2";
  public static final String EFFECTIVE_DATE = "2026-05-29";
  public static final String TITLE = "Thỏa thuận bảo mật, xác thực thông tin và hợp tác gia sư";
  public static final String CONTENT = """
      BÊN A: Nền tảng Gia Sư Sư Phạm, bao gồm đơn vị vận hành, quản trị viên được ủy quyền và hệ thống kỹ thuật dùng để cung cấp dịch vụ kết nối gia sư.
      BÊN B: Cá nhân đăng ký, xác thực và sử dụng tài khoản gia sư trên nền tảng.

      Bằng việc bấm xác nhận, ký điện tử hoặc gửi hồ sơ xác thực trên nền tảng, Bên B xác nhận đã đọc, hiểu và đồng ý bị ràng buộc bởi toàn bộ nội dung thỏa thuận này.

      ĐIỀU 1. XÁC THỰC THÔNG TIN VÀ GIẤY TỜ
      1.1. Bên B cam kết mọi thông tin cá nhân, số điện thoại, email, trường học, chuyên ngành, kinh nghiệm giảng dạy, bằng cấp, chứng chỉ và giấy tờ xác minh đã cung cấp cho Bên A là chính xác, hợp pháp và thuộc quyền sử dụng của Bên B.
      1.2. Bên B cam kết không sử dụng giấy tờ tùy thân, thẻ sinh viên, bằng cấp, chứng chỉ, hình ảnh, tài khoản hoặc thông tin cá nhân của người khác.
      1.3. Bên B đồng ý để Bên A kiểm tra tính hợp lệ của hồ sơ bằng biện pháp thủ công hoặc tự động, bao gồm kiểm tra trùng lặp file, đối chiếu dữ liệu, phân tích rủi ro và yêu cầu bổ sung tài liệu khi cần thiết.
      1.4. Bên A có quyền từ chối, tạm dừng hoặc yêu cầu xác thực lại nếu hồ sơ thiếu thông tin, có dấu hiệu giả mạo, trùng lặp, không nhất quán hoặc vượt ngưỡng rủi ro nội bộ.

      ĐIỀU 2. BẢO MẬT THÔNG TIN HỌC VIÊN VÀ PHỤ HUYNH
      2.1. Bên B cam kết bảo mật toàn bộ thông tin học viên, phụ huynh, người giám hộ, lịch học, địa chỉ, số điện thoại, nhu cầu học tập, tình trạng học tập, học phí, nội dung trao đổi và mọi dữ liệu phát sinh trong quá trình sử dụng nền tảng.
      2.2. Bên B không được sao chép, tiết lộ, mua bán, chuyển giao, đăng tải công khai hoặc sử dụng thông tin học viên/phụ huynh ngoài mục đích giảng dạy đã được Bên A cho phép.
      2.3. Nghĩa vụ bảo mật tiếp tục có hiệu lực kể cả sau khi lớp học kết thúc, tài khoản bị khóa, quyền gia sư bị thu hồi hoặc Bên B ngừng sử dụng nền tảng.

      ĐIỀU 3. QUY TẮC HỢP TÁC VÀ HÀNH VI BỊ CẤM
      3.1. Bên B cam kết cư xử chuyên nghiệp, đúng mực, tôn trọng học viên và phụ huynh; không quấy rối, đe dọa, xúc phạm, phân biệt đối xử, khai thác thông tin riêng tư hoặc có hành vi gây hại.
      3.2. Bên B không được tự ý chèo kéo học viên/phụ huynh ra ngoài nền tảng, thu tiền riêng, chuyển lớp, đổi lịch, hủy lớp hoặc thỏa thuận học phí ngoài quy trình của Bên A nếu chưa được Bên A chấp thuận.
      3.3. Bên B không được tạo nhiều tài khoản để né kiểm duyệt, dùng giấy tờ trùng lặp giữa nhiều tài khoản, thao túng đánh giá, gian lận payout hoặc thực hiện hành vi làm sai lệch dữ liệu vận hành.

      ĐIỀU 4. XỬ LÝ DỮ LIỆU CÁ NHÂN VÀ HỒ SƠ XÁC MINH
      4.1. Bên B đồng ý để Bên A thu thập, lưu trữ, kiểm tra và xử lý dữ liệu cá nhân, giấy tờ xác minh, lịch sử hoạt động, lịch sử giao dịch, đánh giá, khiếu nại, nhật ký truy cập và các thông tin liên quan nhằm mục đích xác minh danh tính, vận hành dịch vụ, phòng chống gian lận, bảo vệ người dùng và tuân thủ yêu cầu pháp luật.
      4.2. Bên A áp dụng biện pháp kiểm soát truy cập, lưu trữ riêng tư, ghi nhận audit log và giới hạn quyền xem giấy tờ đối với tài liệu nhạy cảm. Bên B hiểu rằng việc xử lý dữ liệu xác minh là điều kiện cần để hồ sơ gia sư được xét duyệt.
      4.3. Bên B có trách nhiệm thông báo kịp thời nếu thông tin cá nhân, số điện thoại, email, giấy tờ, bằng cấp hoặc chứng chỉ đã cung cấp có thay đổi, hết hạn, bị thu hồi hoặc không còn chính xác.

      ĐIỀU 5. QUYỀN KIỂM TRA, TẠM KHÓA VÀ THU HỒI QUYỀN GIA SƯ
      5.1. Bên A có quyền yêu cầu Bên B bổ sung giấy tờ, ký lại thỏa thuận, xác minh lại danh tính hoặc giải trình khi phát hiện dấu hiệu rủi ro, khiếu nại, dữ liệu bất thường hoặc vi phạm quy định.
      5.2. Bên A có quyền tạm khóa tài khoản, gỡ hồ sơ công khai, tạm dừng payout, hủy quyền nhận lớp hoặc thu hồi quyền gia sư nếu Bên B cung cấp thông tin sai, dùng giấy tờ giả, tiết lộ dữ liệu học viên, giao dịch ngoài nền tảng hoặc vi phạm nghĩa vụ bảo mật.
      5.3. Trường hợp Bên B tải lại giấy tờ sau khi đã được duyệt, Bên B hiểu rằng hồ sơ có thể bị chuyển về trạng thái chờ xác thực lại cho đến khi admin duyệt tài liệu mới.

      ĐIỀU 6. TRÁCH NHIỆM KHI VI PHẠM
      6.1. Bên B chịu trách nhiệm với mọi thiệt hại, khiếu nại, tranh chấp, tổn thất hoặc chi phí phát sinh do việc cung cấp thông tin sai, sử dụng giấy tờ giả, tiết lộ dữ liệu học viên, giao dịch ngoài nền tảng hoặc vi phạm thỏa thuận này.
      6.2. Bên A có quyền lưu giữ hồ sơ vi phạm, bằng chứng ký điện tử, audit log, bản sao giấy tờ đã nộp và dữ liệu liên quan để phục vụ xử lý nội bộ, giải quyết tranh chấp, phòng chống gian lận hoặc cung cấp theo yêu cầu hợp lệ của cơ quan có thẩm quyền.

      ĐIỀU 7. GIÁ TRỊ XÁC NHẬN ĐIỆN TỬ
      7.1. Bên B đồng ý rằng thao tác bấm xác nhận/ký điện tử trên nền tảng, kèm theo họ tên, tài khoản, thời điểm ký, địa chỉ IP, user-agent, phiên bản điều khoản và mã băm nội dung điều khoản, là bằng chứng thể hiện ý chí chấp thuận của Bên B đối với thỏa thuận này.
      7.2. Mỗi phiên bản thỏa thuận được định danh bằng commitment_version và accepted_terms_hash. Nếu Bên A ban hành phiên bản điều khoản mới có thay đổi quan trọng, Bên B có thể được yêu cầu ký lại trước khi tiếp tục được duyệt hoặc sử dụng đầy đủ quyền gia sư.

      ĐIỀU 8. HIỆU LỰC
      8.1. Thỏa thuận này có hiệu lực từ thời điểm Bên B xác nhận/ký điện tử trên nền tảng.
      8.2. Nếu một phần của thỏa thuận bị vô hiệu hoặc không thể thực thi, các phần còn lại vẫn tiếp tục có hiệu lực trong phạm vi tối đa được phép.
      8.3. Bên B xác nhận đã đọc, hiểu, đồng ý tự nguyện và chịu trách nhiệm thực hiện toàn bộ nội dung thỏa thuận này.
      """;
  public static final String CONTENT_HASH = sha256(CONTENT);

  private VerificationTerms() {
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash verification terms", ex);
    }
  }
}
