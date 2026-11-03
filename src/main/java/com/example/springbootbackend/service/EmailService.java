package com.example.springbootbackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.mail.from:noreply@equine.vn}")
    private String fromEmail;

    @Value("${app.mail.sender-name:Hệ thống Quản lý Huấn luyện Ngựa đua Equine}")
    private String senderName;

    public EmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendStaffAccountCreated(String toEmail, String fullName, String roleName, String temporaryPassword, String phone) {
        String subject = "[Equine] Cấp tài khoản nhân sự mới - Câu lạc bộ Ngựa đua";
        String phoneDisplay = StringUtils.hasText(phone) ? phone : "Chưa cập nhật";
        String friendlyRole = formatRoleName(roleName);

        String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #101732; line-height: 1.6; background-color: #f4f7fb; margin: 0; padding: 20px; }
                        .container { max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
                        .header { background: #1040C5; color: #ffffff; padding: 24px; text-align: center; }
                        .header h1 { margin: 0; font-size: 20px; font-weight: 600; letter-spacing: 0.5px; }
                        .body { padding: 32px 28px; }
                        .welcome { font-size: 16px; font-weight: 600; margin-bottom: 16px; color: #101732; }
                        .info-box { background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 16px 20px; margin: 20px 0; }
                        .info-table { width: 100%%; border-collapse: collapse; }
                        .info-table td { padding: 10px 0; font-size: 14px; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
                        .info-table tr:last-child td { border-bottom: none; }
                        .label-col { color: #64748b; font-weight: 500; width: 38%%; }
                        .value-col { font-weight: 600; color: #0f172a; text-align: left; }
                        .password-box { background: #e0f2fe; border: 1px solid #bae6fd; border-radius: 6px; padding: 14px 18px; margin: 20px 0; }
                        .password-code { font-family: 'Courier New', Courier, monospace; font-size: 18px; font-weight: 700; color: #0369a1; letter-spacing: 1px; }
                        .notice { background: #fffbeb; border-left: 4px solid #f59e0b; padding: 14px; font-size: 13px; color: #92400e; margin: 20px 0; border-radius: 4px; }
                        .footer { background: #f8fafc; padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #e2e8f0; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>HỆ THỐNG QUẢN LÝ HUẤN LUYỆN NGỰA ĐUA EQUINE</h1>
                        </div>
                        <div class="body">
                            <div class="welcome">Xin chào %s,</div>
                            <p>Tài khoản nhân sự của bạn đã được Quản lý Câu lạc bộ khởi tạo thành công trên hệ thống.</p>
                            <div class="info-box">
                                <table class="info-table" role="presentation" border="0" cellpadding="0" cellspacing="0" style="width: 100%%; border-collapse: collapse;">
                                    <tr>
                                        <td class="label-col" style="padding: 10px 0; color: #64748b; width: 38%%; border-bottom: 1px solid #e2e8f0;">Họ và tên:</td>
                                        <td class="value-col" style="padding: 10px 0; font-weight: 600; color: #0f172a; border-bottom: 1px solid #e2e8f0;">%s</td>
                                    </tr>
                                    <tr>
                                        <td class="label-col" style="padding: 10px 0; color: #64748b; width: 38%%; border-bottom: 1px solid #e2e8f0;">Email đăng nhập:</td>
                                        <td class="value-col" style="padding: 10px 0; font-weight: 600; color: #0f172a; border-bottom: 1px solid #e2e8f0;"><a href="mailto:%s" style="color: #1040C5; text-decoration: none;">%s</a></td>
                                    </tr>
                                    <tr>
                                        <td class="label-col" style="padding: 10px 0; color: #64748b; width: 38%%; border-bottom: 1px solid #e2e8f0;">Số điện thoại:</td>
                                        <td class="value-col" style="padding: 10px 0; font-weight: 600; color: #0f172a; border-bottom: 1px solid #e2e8f0;">%s</td>
                                    </tr>
                                    <tr>
                                        <td class="label-col" style="padding: 10px 0; color: #64748b; width: 38%%;">Vai trò chuyên môn:</td>
                                        <td class="value-col" style="padding: 10px 0; font-weight: 600; color: #0f172a;">%s</td>
                                    </tr>
                                </table>
                            </div>
                            <div class="password-box">
                                <div class="label" style="color: #0369a1; margin-bottom: 6px;">MẬT KHẨU TẠM THỜI:</div>
                                <div class="password-code">%s</div>
                            </div>
                            <div class="notice">
                                <strong>Lưu ý bắt buộc:</strong> Đây là mật khẩu tạm thời. Vì lý do an toàn, bạn sẽ được yêu cầu đổi mật khẩu mới ngay trong lần đầu tiên đăng nhập vào hệ thống.
                            </div>
                            <p style="margin-top: 24px; font-size: 14px; color: #475569;">Trân trọng,<br><strong>Ban Quản lý Câu lạc bộ</strong></p>
                        </div>
                        <div class="footer">
                            Email này được gửi tự động từ hệ thống Equine Racehorse Management. Vui lòng không trả lời thư này.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(fullName, fullName, toEmail, toEmail, phoneDisplay, friendlyRole, temporaryPassword);

        String plainText = """
                Xin chào %s,
                
                Tài khoản nhân sự của bạn đã được cấp trên Hệ thống Quản lý Huấn luyện Ngựa đua Equine.
                - Email đăng nhập: %s
                - Số điện thoại: %s
                - Vai trò: %s
                - Mật khẩu tạm thời: %s
                
                Lưu ý: Bạn bắt buộc phải đổi mật khẩu ở lần đăng nhập đầu tiên.
                """.formatted(fullName, toEmail, phoneDisplay, friendlyRole, temporaryPassword);

        sendEmail(toEmail, subject, htmlContent, plainText);
    }

    private String formatRoleName(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            return "Nhân sự CLB";
        }
        return switch (roleName.trim().toUpperCase()) {
            case "HEAD_TRAINER" -> "Huấn luyện viên trưởng (Head Trainer)";
            case "VETERINARIAN" -> "Bác sĩ thú y (Veterinarian)";
            case "GROOM" -> "Nhân viên chăm sóc ngựa (Groom)";
            case "CLUB_MANAGER" -> "Quản lý Câu lạc bộ (Club Manager)";
            case "HORSE_OWNER" -> "Chủ sở hữu ngựa (Horse Owner)";
            default -> roleName;
        };
    }

    public void sendPasswordResetCode(String toEmail, String fullName, String code) {
        String subject = "[Equine] Mã xác nhận đặt lại mật khẩu";
        String displayName = StringUtils.hasText(fullName) ? fullName : "Quý khách";

        String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #101732; line-height: 1.6; background-color: #f4f7fb; margin: 0; padding: 20px; }
                        .container { max-width: 540px; margin: 0 auto; background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
                        .header { background: #1040C5; color: #ffffff; padding: 20px; text-align: center; }
                        .header h1 { margin: 0; font-size: 18px; font-weight: 600; }
                        .body { padding: 28px 24px; }
                        .code-box { text-align: center; background: #f8fafc; border: 2px dashed #1040C5; border-radius: 8px; padding: 18px; margin: 24px 0; }
                        .otp-code { font-family: 'Courier New', Courier, monospace; font-size: 32px; font-weight: 800; color: #1040C5; letter-spacing: 6px; }
                        .expire-tip { font-size: 13px; color: #64748b; margin-top: 8px; }
                        .warning { font-size: 13px; color: #b45309; background: #fffbeb; border-left: 3px solid #f59e0b; padding: 12px; margin: 18px 0; border-radius: 4px; }
                        .footer { background: #f8fafc; padding: 16px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #e2e8f0; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>YÊU CẦU ĐẶT LẠI MẬT KHẨU</h1>
                        </div>
                        <div class="body">
                            <p>Xin chào <strong>%s</strong>,</p>
                            <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản <strong>%s</strong> tại hệ thống Equine.</p>
                            <p>Vui lòng nhập mã xác nhận (OTP) dưới đây để tiếp tục:</p>
                            <div class="code-box">
                                <div class="otp-code">%s</div>
                                <div class="expire-tip">Mã có hiệu lực trong <strong>15 phút</strong></div>
                            </div>
                            <div class="warning">
                                <strong>Bảo mật:</strong> Tuyệt đối không chia sẻ mã này cho bất kỳ ai. Nếu bạn không gửi yêu cầu này, vui lòng bỏ qua email và bảo vệ tài khoản của bạn.
                            </div>
                            <p style="margin-top: 24px; font-size: 14px; color: #475569;">Trân trọng,<br><strong>Đội ngũ Kỹ thuật Equine</strong></p>
                        </div>
                        <div class="footer">
                            Email này được gửi tự động từ hệ thống Equine Racehorse Management.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(displayName, toEmail, code);

        String plainText = """
                Xin chào %s,
                
                Mã xác nhận (OTP) để đặt lại mật khẩu tài khoản %s của bạn là: %s
                Mã có hiệu lực trong vòng 15 phút.
                
                Vui lòng không chia sẻ mã này cho bất kỳ ai.
                """.formatted(displayName, toEmail, code);

        sendEmail(toEmail, subject, htmlContent, plainText);
    }

    public void sendPasswordResetSuccess(String toEmail, String fullName) {
        String subject = "[Equine] Mật khẩu tài khoản đã được thay đổi thành công";
        String displayName = StringUtils.hasText(fullName) ? fullName : "Quý khách";

        String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #101732; line-height: 1.6; background-color: #f4f7fb; margin: 0; padding: 20px; }
                        .container { max-width: 540px; margin: 0 auto; background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
                        .header { background: #15803D; color: #ffffff; padding: 20px; text-align: center; }
                        .header h1 { margin: 0; font-size: 18px; font-weight: 600; }
                        .body { padding: 28px 24px; }
                        .footer { background: #f8fafc; padding: 16px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #e2e8f0; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>ĐỔI MẬT KHẨU THÀNH CÔNG</h1>
                        </div>
                        <div class="body">
                            <p>Xin chào <strong>%s</strong>,</p>
                            <p>Mật khẩu tài khoản <strong>%s</strong> của bạn đã được đặt lại thành công.</p>
                            <p>Nếu bạn không thực hiện thay đổi này, hãy liên hệ ngay với Quản lý Câu lạc bộ để được hỗ trợ khóa tài khoản khẩn cấp.</p>
                            <p style="margin-top: 24px; font-size: 14px; color: #475569;">Trân trọng,<br><strong>Đội ngũ Kỹ thuật Equine</strong></p>
                        </div>
                        <div class="footer">
                            Email này được gửi tự động từ hệ thống Equine Racehorse Management.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(displayName, toEmail);

        String plainText = """
                Xin chào %s,
                
                Mật khẩu tài khoản %s của bạn đã được cập nhật thành công.
                Nếu không phải bạn thực hiện, vui lòng liên hệ Quản lý CLB ngay lập tức.
                """.formatted(displayName, toEmail);

        sendEmail(toEmail, subject, htmlContent, plainText);
    }

    private void sendEmail(String toEmail, String subject, String htmlContent, String plainTextFallback) {
        boolean sent = false;
        if (mailSender != null && StringUtils.hasText(mailHost) && StringUtils.hasText(mailUsername)) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromEmail, senderName);
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(plainTextFallback, htmlContent);
                mailSender.send(message);
                sent = true;
                log.info("[EMAIL SERVICE] Gửi email thành công qua SMTP tới: {}", toEmail);
            } catch (Exception ex) {
                log.warn("[EMAIL SERVICE] Không thể gửi email qua SMTP: {}. Chuyển sang ghi log máy chủ.", ex.getMessage());
            }
        }

        // Always print clean console log for development and demo inspection
        System.out.println("\n" + "=".repeat(75));
        System.out.println("[EMAIL NOTIFICATION" + (sent ? " - ĐÃ GỬI SMTP]" : " - MÔ PHỎNG / LOG MÁY CHỦ]"));
        System.out.println("Gửi đến: " + toEmail);
        System.out.println("Tiêu đề : " + subject);
        System.out.println("-".repeat(75));
        System.out.println(plainTextFallback.trim());
        System.out.println("=".repeat(75) + "\n");
    }
}

