package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Registration objects must live in a private bucket; URLs expire after five minutes. */
@Service
public class RegistrationStorage {
  private final String base, key, bucket;
  private final ObjectMapper json;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public RegistrationStorage(
      @Value("${app.supabase.url:}") String base,
      @Value("${app.supabase.secret-key:}") String key,
      @Value("${app.supabase.registration-bucket:horse-registration-files}") String bucket,
      ObjectMapper json) {
    this.base = base.replaceAll("/+$", "");
    this.key = key;
    this.bucket = bucket;
    this.json = json;
  }

  public record FileData(byte[] bytes, String type, String extension, String filename) {}

  public FileData validate(MultipartFile file, boolean image) {
    if (file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "File must not be empty");
    if (file.getSize() > (image ? 5L : 10L) * 1024 * 1024)
      throw new ResponseStatusException(
          PAYLOAD_TOO_LARGE, image ? "Image exceeds 5 MB" : "Document exceeds 10 MB");
    try {
      byte[] b = file.getBytes();
      String type, ext;
      if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) {
        type = "image/jpeg";
        ext = "jpg";
      } else if (b.length >= 8
          && Arrays.equals(
              Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
        type = "image/png";
        ext = "png";
      } else if (b.length >= 12
          && new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
          && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
        type = "image/webp";
        ext = "webp";
      } else if (!image
          && b.length >= 5
          && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
        type = "application/pdf";
        ext = "pdf";
      } else throw new ResponseStatusException(BAD_REQUEST, "Unsupported file content");
      String name = Objects.toString(file.getOriginalFilename(), "").replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1);
      String lower = name.toLowerCase(Locale.ROOT);
      if (name.length() > 255
          || !type.equals(file.getContentType())
          || !(lower.endsWith("." + ext) || (ext.equals("jpg") && lower.endsWith(".jpeg"))))
        throw new ResponseStatusException(
            BAD_REQUEST, "File extension, content and Content-Type must match");
      return new FileData(b, type, ext, name);
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(BAD_REQUEST, "Cannot read file");
    }
  }

  public String upload(String requestId, FileData data) {
    try {
      if (json.readTree(send("GET", "/bucket/" + bucket, null, null))
          .path("public")
          .asBoolean(true))
        throw new ResponseStatusException(
            SERVICE_UNAVAILABLE, "Registration storage bucket must be private");
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(BAD_GATEWAY, "Invalid storage response");
    }
    String path = "registrations/" + requestId + "/" + UUID.randomUUID() + "." + data.extension();
    send("POST", "/object/" + bucket + "/" + path, data.type(), data.bytes());
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) {
                try {
                  send("DELETE", "/object/" + bucket + "/" + path, null, null);
                } catch (RuntimeException e) {
                  org.slf4j.LoggerFactory.getLogger(RegistrationStorage.class)
                      .warn("Cannot clean up rolled-back registration object {}", path);
                }
              }
            }
          });
    return path;
  }

  public String signedUrl(String path) {
    if (!path.matches("registrations/[0-9a-f-]{36}/[0-9a-f-]{36}\\.(jpg|png|webp|pdf)"))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid registration object path");
    try {
      String signed =
          json.readTree(
                  send(
                      "POST",
                      "/object/sign/" + bucket + "/" + path,
                      "application/json",
                      "{\"expiresIn\":300}".getBytes(StandardCharsets.UTF_8)))
              .path("signedURL")
              .asText();
      if (!signed.startsWith("/object/sign/")) throw new java.io.IOException();
      return base + "/storage/v1" + signed;
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(BAD_GATEWAY, "Cannot sign file URL");
    }
  }

  public void delete(String path) {
    if (!path.matches("registrations/[0-9a-f-]{36}/[0-9a-f-]{36}\\.(jpg|png|webp|pdf)"))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid registration object path");
    send("DELETE", "/object/" + bucket + "/" + path, null, null);
  }

  private String send(String method, String path, String type, byte[] data) {
    if (base.isBlank() || key.isBlank() || !bucket.matches("[a-zA-Z0-9_-]+"))
      throw new ResponseStatusException(
          SERVICE_UNAVAILABLE, "Registration storage is not configured");
    var builder =
        HttpRequest.newBuilder(URI.create(base + "/storage/v1" + path))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", key)
            .header("x-upsert", "false");
    if (type != null) builder.header("Content-Type", type);
    try {
      var response =
          client.send(
              builder
                  .method(
                      method,
                      data == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofByteArray(data))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ResponseStatusException(
            BAD_GATEWAY, "Registration storage unavailable; check private bucket configuration");
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Storage interrupted");
    } catch (java.io.IOException e) {
      throw new ResponseStatusException(BAD_GATEWAY, "Cannot connect to registration storage");
    }
  }
}
