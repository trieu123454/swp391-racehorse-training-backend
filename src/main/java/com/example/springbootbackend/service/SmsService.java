package com.example.springbootbackend.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${app.sms.twilio.account-sid:${TWILIO_ACCOUNT_SID:}}")
    private String twilioAccountSid;

    @Value("${app.sms.twilio.auth-token:${TWILIO_AUTH_TOKEN:}}")
    private String twilioAuthToken;

    @Value("${app.sms.twilio.from-number:${TWILIO_PHONE_NUMBER:}}")
    private String twilioFromNumber;

    @Value("${app.sms.speedsms.access-token:${SPEEDSMS_ACCESS_TOKEN:}}")
    private String speedSmsAccessToken;

    public void sendStaffAccountCreated(String phone, String email, String temporaryPassword) {
        if (!StringUtils.hasText(phone)) {
            return;
        }
        String normalizedPhone = normalizePhone(phone);
        String content = "[Equine] Tai khoan nhan su cua ban da duoc khoi tao. Email: "
                + email + ", Mat khau tam thoi: " + temporaryPassword
                + ". Vui long dang nhap va doi mat khau o lan dau tien.";

        sendSms(normalizedPhone, content);
    }

    public void sendPasswordResetCode(String phone, String code) {
        if (!StringUtils.hasText(phone)) {
            return;
        }
        String normalizedPhone = normalizePhone(phone);
        String content = "[Equine] Ma xac nhan (OTP) dat lai mat khau cua ban la: "
                + code + ". Ma co hieu luc trong 15 phut. Vui long khong chia se cho nguoi khac.";

        sendSms(normalizedPhone, content);
    }

    private void sendSms(String phone, String content) {
        boolean sent = false;

        // Ưu tiên 1: Gửi qua Twilio nếu có cấu hình
        if (StringUtils.hasText(twilioAccountSid) && StringUtils.hasText(twilioAuthToken) && StringUtils.hasText(twilioFromNumber)) {
            sent = sendViaTwilio(phone, content);
        }

        // Ưu tiên 2: Gửi qua SpeedSMS (SMS Brandname / CSKH Việt Nam) nếu có cấu hình
        if (!sent && StringUtils.hasText(speedSmsAccessToken)) {
            sent = sendViaSpeedSms(phone, content);
        }

        // Ghi log máy chủ / console để kiểm tra và đối chiếu
        System.out.println("\n" + "-".repeat(75));
        System.out.println("[SMS NOTIFICATION - " + (sent ? "ĐÃ GỬI THÀNH CÔNG TỚI NHÀ MẠNG]" : "MÔ PHỎNG / LOG MÁY CHỦ]"));
        System.out.println("Gửi đến SĐT: " + phone);
        System.out.println("Nội dung   : " + content);
        System.out.println("-".repeat(75) + "\n");
    }

    private boolean sendViaTwilio(String phone, String content) {
        try {
            String toE164 = toE164(phone);
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid.trim() + "/Messages.json";
            String form = "To=" + URLEncoder.encode(toE164, StandardCharsets.UTF_8)
                    + "&From=" + URLEncoder.encode(twilioFromNumber.trim(), StandardCharsets.UTF_8)
                    + "&Body=" + URLEncoder.encode(content, StandardCharsets.UTF_8);

            String auth = Base64.getEncoder().encodeToString(
                    (twilioAccountSid.trim() + ":" + twilioAuthToken.trim()).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[SMS SERVICE] Gửi SMS thành công qua Twilio tới {}: {}", toE164, response.statusCode());
                return true;
            } else {
                log.warn("[SMS SERVICE] Twilio phản hồi mã {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("[SMS SERVICE] Lỗi khi gửi SMS qua Twilio: {}", ex.getMessage());
        }
        return false;
    }

    private boolean sendViaSpeedSms(String phone, String content) {
        try {
            String url = "https://api.speedsms.vn/index.php/sms/send";
            String json = """
                    {"to": ["%s"], "content": "%s", "sms_type": 2}
                    """.formatted(phone, content.replace("\"", "\\\""));

            String auth = Base64.getEncoder().encodeToString(
                    (speedSmsAccessToken.trim() + ":x").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[SMS SERVICE] Gửi SMS thành công qua SpeedSMS tới {}: {}", phone, response.statusCode());
                return true;
            } else {
                log.warn("[SMS SERVICE] SpeedSMS phản hồi mã {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("[SMS SERVICE] Lỗi khi gửi SMS qua SpeedSMS: {}", ex.getMessage());
        }
        return false;
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9+]", "");
    }

    private String toE164(String phone) {
        String cleaned = normalizePhone(phone);
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        if (cleaned.startsWith("0")) {
            return "+84" + cleaned.substring(1);
        }
        return "+84" + cleaned;
    }
}
 