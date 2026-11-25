package com.example.springbootbackend.service;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves the legacy database marker to the actual asset's URL and format. */
@Service
public class CloudinaryHorseImages {
  private final String cloud, key, secret, apiBase;
  private final ObjectMapper json;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @Autowired
  public CloudinaryHorseImages(
      @Value("${app.cloudinary.cloud-name:${CLOUDINARY_CLOUD_NAME:}}") String cloud,
      @Value("${app.cloudinary.api-key:${CLOUDINARY_API_KEY:}}") String key,
      @Value("${app.cloudinary.api-secret:${CLOUDINARY_API_SECRET:}}") String secret,
      ObjectMapper json) {
    this(cloud, key, secret, json, "https://api.cloudinary.com/v1_1/");
  }

  CloudinaryHorseImages(
      String cloud, String key, String secret, ObjectMapper json, String apiBase) {
    this.cloud = cloud;
    this.key = key;
    this.secret = secret;
    this.json = json;
    this.apiBase = apiBase;
  }

  public Map<String, Object> resolve(String path) {
    boolean document = path.matches("horse-documents/cloudinary/[0-9a-fA-F-]{36}\\.(pdf|jpg|jpeg|png|webp)");
    if (!document && !path.matches("horses/cloudinary/[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp)"))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid Cloudinary image path");
    if (!cloud.matches("[a-zA-Z0-9_-]+") || key.isBlank() || secret.isBlank())
      throw new ResponseStatusException(
          SERVICE_UNAVAILABLE, "Cloudinary storage is not configured");
    String folder = document ? "horse-documents" : "horses";
    String id = path.substring((folder + "/cloudinary/").length(), path.lastIndexOf('.'));
    String authorization = Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
    var request = HttpRequest.newBuilder(
        URI.create(apiBase + cloud + "/resources/image/upload/" + folder + "%2F" + id))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Basic " + authorization)
        .GET()
        .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        if (document)
          return Map.of("available", false, "unavailableReason", "FILE_NOT_FOUND");
        throw new ResponseStatusException(NOT_FOUND, "Horse image not found");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ResponseStatusException(BAD_GATEWAY, "Cloudinary storage unavailable");
      var asset = json.readTree(response.body());
      String url = asset.path("secure_url").asText();
      URI image = URI.create(url);
      if (!"upload".equals(asset.path("type").asText())
          || !"https".equals(image.getScheme())
          || !"res.cloudinary.com".equals(image.getHost())
          || !image.getPath().startsWith("/" + cloud + "/image/upload/"))
        throw new ResponseStatusException(BAD_GATEWAY, "Invalid Cloudinary image response");
      if (document) {
        return Map.of("hasImage", true, "url", url);
      }
      String uploadPrefix = "https://res.cloudinary.com/" + cloud + "/image/upload/";
      String optimizedUrl =
          url.startsWith(uploadPrefix) && !url.startsWith(uploadPrefix + "f_auto,")
              ? uploadPrefix + "f_auto,q_auto:good,w_720,c_limit/" + url.substring(uploadPrefix.length())
              : url;
      return Map.of("hasImage", true, "url", optimizedUrl, "expiresIn", 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Storage interrupted", e);
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw new ResponseStatusException(BAD_GATEWAY, "Cannot resolve Cloudinary image", e);
    }
  }
}
