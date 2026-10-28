package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class HorseStorage {
    private static final Logger log = LoggerFactory.getLogger(HorseStorage.class);
    private final String url, key, bucket;
    private final String provider, cloudName, cloudApiKey, cloudApiSecret;
    private final VetData db;
    private final ObjectMapper json;
    private final VetTime time;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public HorseStorage(
            @Value("${app.supabase.url:}") String url,
            @Value("${app.supabase.secret-key:}") String key,
            @Value("${app.supabase.bucket:horse-images}") String bucket,
            @Value("${app.storage.provider:cloudinary}") String provider,
            @Value("${app.cloudinary.cloud-name:}") String cloudName,
            @Value("${app.cloudinary.api-key:}") String cloudApiKey,
            @Value("${app.cloudinary.api-secret:}") String cloudApiSecret,
            VetData db,
            ObjectMapper json,
            VetTime time) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.bucket = bucket;
        this.provider = Objects.toString(provider, "cloudinary").trim().toLowerCase(Locale.ROOT);
        this.cloudName = Objects.toString(cloudName, "").trim();
        this.cloudApiKey = Objects.toString(cloudApiKey, "").trim();
        this.cloudApiSecret = Objects.toString(cloudApiSecret, "").trim();
        this.db = db;
        this.json = json;
        this.time = time;
    }

    public HorseStorage(String url, String key, String bucket, VetData db, ObjectMapper json) {
        this(url, key, bucket, "supabase", "", "", "", db, json, new VetTime());
    }

    public Map<String, Object> upload(MultipartFile file, long actor) { return upload(file, actor, "horses"); }
    public Map<String, Object> uploadIncident(MultipartFile file, long actor) { return upload(file, actor, "incidents"); }

    public String uploadRegistrationDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Giấy tờ không được rỗng");
        if (file.getSize() > 10 * 1024 * 1024) throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Mỗi giấy tờ không được vượt quá 10 MB");
        try {
            byte[] bytes = file.getBytes();
            String type = detectDocument(bytes);
            String supplied = Objects.toString(file.getContentType(), "").toLowerCase(Locale.ROOT);
            if (!type.equals(supplied) && !"application/octet-stream".equals(supplied))
                throw new ResponseStatusException(BAD_REQUEST, "Định dạng giấy tờ không khớp nội dung file");
            String extension = switch (type) { case "application/pdf" -> ".pdf"; case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".webp"; };
            String fileId = UUID.randomUUID().toString();
            String path = isCloudinaryProvider() ? "horse-documents/cloudinary/" + fileId + extension : "horse-documents/" + fileId + extension;
            if (isCloudinaryProvider()) uploadCloudinary("horse-documents/" + fileId + extension, "raw", "authenticated", extension, type, bytes);
            else send("/object/" + bucket + "/" + path, type, bytes);
            return path;
        } catch (IOException e) { throw new ResponseStatusException(BAD_REQUEST, "Không đọc được giấy tờ"); }
    }

    public String uploadRegistrationHorseImage(MultipartFile file, long actor) {
        if (file == null || file.isEmpty()) return null;
        return (String) upload(file, actor, "horses").get("imagePath");
    }

    private Map<String, Object> upload(MultipartFile file, long actor, String folder) {
        if (file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Ảnh không được rỗng");
        if (file.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Ảnh không được vượt quá 5 MB");
        try {
            byte[] bytes = file.getBytes();
            String type = detect(bytes);
            String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
            String extension = switch (type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".webp"; };
            if (!type.equals(file.getContentType()) || !(name.endsWith(extension) || (extension.equals(".jpg") && name.endsWith(".jpeg"))))
                throw new ResponseStatusException(BAD_REQUEST, "Chỉ chấp nhận ảnh JPG, PNG, WebP đúng định dạng");
            String fileId = UUID.randomUUID().toString();
            boolean privateIncident = "incidents".equals(folder);
            String cloudinaryPrefix = privateIncident ? "incidents/cloudinary-private" : "horses/cloudinary";
            String path = isCloudinaryProvider() ? cloudinaryPrefix + "/" + fileId + extension : folder + "/" + fileId + extension;
            if (isCloudinaryProvider()) uploadCloudinary(folder + "/" + fileId, "image", privateIncident ? "authenticated" : "upload", extension, type, bytes);
            else send("/object/" + bucket + "/" + path, type, bytes);
            db.update("INSERT INTO horse_image_uploads(object_path,uploaded_by,created_at) VALUES (?,?,?)", path, actor, time.utcNow());
            return Map.of("imagePath", path);
        } catch (IOException e) { throw new ResponseStatusException(BAD_REQUEST, "Không đọc được ảnh"); }
    }

    public static String detect(byte[] b) {
        if (b.length >= 12) {
            if ((b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return "image/jpeg";
            if (Arrays.equals(Arrays.copyOf(b, 8), new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 })) return "image/png";
            if (new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        }
        throw new ResponseStatusException(BAD_REQUEST, "Nội dung file không phải ảnh JPG, PNG hoặc WebP");
    }

    private static String detectDocument(byte[] b) {
        if (b.length >= 5 && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        return detect(b);
    }

    public Map<String, Object> signedUrl(String path) {
        return signedUrl(path, null);
    }

    public Map<String, Object> signedUrl(String path, String downloadFilename) {
        if (path == null) return Map.of("hasImage", false);
        if (isCloudinaryPath(path)) return cloudinaryUrl(path, downloadFilename);
        if (!path.matches("(horses|incidents)/[0-9a-fA-F-]{36}\\.(jpg|png|webp)|horse-documents/[0-9a-fA-F-]{36}\\.(jpg|png|webp|pdf)"))
            throw new ResponseStatusException(BAD_REQUEST, "Đường dẫn tệp không hợp lệ");
        String response = send("/object/sign/" + bucket + "/" + path, "application/json", "{\"expiresIn\":300}".getBytes(StandardCharsets.UTF_8));
        if (response == null) return Map.of("hasImage", false);
        try {
            String signed = json.readTree(response).path("signedURL").asText();
            if (!signed.startsWith("/object/sign/")) throw new IllegalArgumentException();
            String signedUrl = url + "/storage/v1" + signed;
            if (downloadFilename != null && !downloadFilename.isBlank()) signedUrl += "&download=" + encode(downloadFilename);
            return Map.of("hasImage", true, "url", signedUrl, "expiresIn", 300);
        } catch (Exception e) { throw new ResponseStatusException(BAD_GATEWAY, "Không lấy được link ảnh"); }
    }

    private boolean isCloudinaryProvider() {
        if (!provider.equals("cloudinary") && !provider.equals("supabase"))
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Storage provider chưa được hỗ trợ");
        return provider.equals("cloudinary");
    }

    private boolean isCloudinaryPath(String path) {
        return path.matches("horses/cloudinary/[0-9a-fA-F-]{36}\\.(jpg|png|webp)"
                + "|incidents/cloudinary-private/[0-9a-fA-F-]{36}\\.(jpg|png|webp)"
                + "|incidents/cloudinary/[0-9a-fA-F-]{36}\\.(jpg|png|webp)"
                + "|horse-documents/cloudinary/[0-9a-fA-F-]{36}\\.(jpg|png|webp|pdf)");
    }

    private Map<String, Object> cloudinaryUrl(String path, String downloadFilename) {
        requireCloudinaryConfig();
        boolean document = path.startsWith("horse-documents/");
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        String publicId = document ? "horse-documents/" + fileName : path.substring(0, path.lastIndexOf('.')).replace("/cloudinary/", "/");
        if (!document) {
            String category = path.startsWith("incidents/") ? "incidents" : "horses";
            publicId = category + "/" + fileName.substring(0, fileName.lastIndexOf('.'));
            if (category.equals("incidents") && path.startsWith("incidents/cloudinary-private/"))
                return cloudinaryDownloadUrl("image", publicId, extension, false);
            String delivery = "https://res.cloudinary.com/" + cloudName + "/image/upload/" + publicId + "." + extension;
            return Map.of("hasImage", true, "url", delivery, "expiresIn", 0);
        }

        return cloudinaryDownloadUrl("raw", publicId, extension, downloadFilename != null && !downloadFilename.isBlank());
    }

    private Map<String, Object> cloudinaryDownloadUrl(String resourceType, String publicId, String extension, boolean attachment) {
        long timestamp = Instant.now().getEpochSecond();
        long expiresAt = timestamp + 300;
        SortedMap<String, String> signedParameters = new TreeMap<>();
        if (attachment) signedParameters.put("attachment", "true");
        signedParameters.put("expires_at", Long.toString(expiresAt));
        signedParameters.put("format", extension);
        signedParameters.put("public_id", publicId);
        signedParameters.put("timestamp", Long.toString(timestamp));
        signedParameters.put("type", "authenticated");
        String signature = apiSignature(signedParameters, cloudApiSecret);
        String query = encodeQuery(signedParameters) + "&api_key=" + encode(cloudApiKey) + "&signature=" + signature;
        String download = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + resourceType + "/download?" + query;
        return Map.of("hasImage", true, "url", download, "expiresIn", 300);
    }

    private void uploadCloudinary(String publicId, String resourceType, String deliveryType, String extension, String contentType, byte[] bytes) {
        requireCloudinaryConfig();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String boundary = "----EquineCloudinary" + UUID.randomUUID().toString().replace("-", "");
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 1024);
            writeFilePart(body, boundary, extension, contentType, bytes);
            writeTextPart(body, boundary, "timestamp", timestamp);
            writeTextPart(body, boundary, "public_id", publicId);
            writeTextPart(body, boundary, "type", deliveryType);
            writeTextPart(body, boundary, "overwrite", "false");
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            String credentials = Base64.getEncoder().encodeToString((cloudApiKey + ":" + cloudApiSecret).getBytes(StandardCharsets.UTF_8));
            URI endpoint = URI.create("https://api.cloudinary.com/v1_1/" + cloudName + "/" + resourceType + "/upload");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Cloudinary upload failed (status={}, resourceType={})", response.statusCode(), resourceType);
                throw new ResponseStatusException(BAD_GATEWAY, "Cloudinary tạm thời không khả dụng, vui lòng thử lại");
            }
            var uploaded = json.readTree(response.body());
            if (!publicId.equals(uploaded.path("public_id").asText()) || !resourceType.equals(uploaded.path("resource_type").asText()))
                throw new ResponseStatusException(BAD_GATEWAY, "Cloudinary trả về thông tin tệp không hợp lệ");
        } catch (ResponseStatusException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Upload bị gián đoạn"); }
          catch (IOException e) { throw new ResponseStatusException(BAD_GATEWAY, "Không kết nối được Cloudinary", e); }
    }

    private void requireCloudinaryConfig() {
        if (cloudName.isBlank() || cloudApiKey.isBlank() || cloudApiSecret.isBlank()
                || !cloudName.matches("[A-Za-z][A-Za-z0-9-]{1,127}"))
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Cloudinary chưa được cấu hình đầy đủ");
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary, String extension, String contentType, byte[] bytes) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload." + extension + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTextPart(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String apiSignature(SortedMap<String, String> parameters, String secret) {
        String serialized = String.join("&", parameters.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((serialized + secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-1 is unavailable", e); }
    }

    private static String encodeQuery(SortedMap<String, String> values) {
        return String.join("&", values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).toList());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String send(String path, String type, byte[] bytes) {
        if (url.isBlank() || key.isBlank() || !bucket.matches("[a-zA-Z0-9_-]+"))
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Storage chưa được cấu hình");
        try {
            var builder = HttpRequest.newBuilder(URI.create(url + "/storage/v1" + path)).timeout(Duration.ofSeconds(30))
                .header("apikey", key).header("Content-Type", type).header("x-upsert", "false");
            if (key.startsWith("eyJ")) builder.header("Authorization", "Bearer " + key);
            var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                String detail = storageErrorDetail(result.body());
                if (path.startsWith("/object/sign/") && isMissingObject(result.statusCode(), detail)) {
                    log.info("Storage image object is missing (bucket={}, status={})", bucket, result.statusCode());
                    return null;
                }
                log.warn("Storage request failed (host={}, status={}, operation={})",
                        URI.create(url).getHost(), result.statusCode(), path.startsWith("/object/sign/") ? "sign" : "upload");
                if (!detail.isBlank()) log.warn("Storage error response: {}", detail);
                throw new ResponseStatusException(BAD_GATEWAY, "Storage tạm thời không khả dụng, vui lòng thử lại");
            }
            return result.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Upload bị gián đoạn"); }
          catch (IOException e) {
              log.warn("Storage connection failed (host={}): {}", URI.create(url).getHost(), e.toString());
              for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause())
                  log.warn("Storage connection cause: {}", cause.toString());
              throw new ResponseStatusException(BAD_GATEWAY, "Không kết nối được Storage", e);
          }
    }

    private String storageErrorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        String detail = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim()
                .replaceAll("sb_secret_[A-Za-z0-9_-]+", "[redacted]")
                .replaceAll("eyJ[A-Za-z0-9_-]+", "[redacted]");
        return detail.length() > 240 ? detail.substring(0, 240) : detail;
    }

    private boolean isMissingObject(int status, String detail) {
        if (status != 400 && status != 404) return false;
        String normalized = detail.toLowerCase(Locale.ROOT);
        return normalized.contains("object not found") || normalized.contains("no such key")
                || normalized.contains("object does not exist");
    }
}
