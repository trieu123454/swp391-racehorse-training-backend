package com.example.springbootbackend.controller;

import static org.assertj.core.api.Assertions.*;

import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.security.JwtService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Real HTTP multipart requests through embedded Tomcat and the storage HTTP adapter. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.config.import=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.url=jdbc:h2:mem:registration_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("test")
class HorseRegistrationMultipartTests {
  static final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  static volatile boolean failUpload = false, publicBucket = false, failSigning = false;
  static final HttpServer storage = startStorage();

  static HttpServer startStorage() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/storage/v1",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body = "{}";
            if (path.contains("/bucket/")) body = "{\"public\":" + publicBucket + "}";
            else if (path.contains("/object/sign/")) {
              if (failSigning) status = 503;
              else
                body =
                    "{\"signedURL\":\"/object/sign/test/"
                        + path.substring(path.lastIndexOf('/') + 1)
                        + "?token=test\"}";
            } else if (exchange.getRequestMethod().equals("DELETE")) objects.remove(path);
            else if (failUpload) status = 503;
            else objects.put(path, exchange.getRequestBody().readAllBytes());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("app.storage.provider", () -> "supabase");
    r.add("app.supabase.url", () -> "http://127.0.0.1:" + storage.getAddress().getPort());
    r.add("app.supabase.secret-key", () -> "test-key");
    r.add("app.supabase.registration-bucket", () -> "test");
  }

  @AfterAll
  static void closeStorage() {
    storage.stop(0);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired AppUserRepository users;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper json;
  final HttpClient client = HttpClient.newHttpClient();
  String owner, other, staff;

  @BeforeEach
  void setup() {
    objects.clear();
    failUpload = false;
    publicBucket = false;
    failSigning = false;
    owner = addUser("HORSE_OWNER");
    other = addUser("HORSE_OWNER");
    staff = addUser("GROOM");
  }

  String addUser(String role) {
    String email = UUID.randomUUID() + "@multipart.test";
    db.update(
        "INSERT INTO users(full_name,email,password_hash,role_id,status) SELECT 'HTTP"
            + " Owner',?,'hash',role_id,'APPROVED' FROM roles WHERE role_name=?",
        email,
        role);
    return com.example.springbootbackend.TestTokens.create(
        jwt, db, users.findByEmailIgnoreCase(email).orElseThrow());
  }

  record Part(String name, String filename, String type, byte[] data) {}

  Part part(String name, String filename, String type, String data) {
    return new Part(name, filename, type, data.getBytes(StandardCharsets.UTF_8));
  }

  Part request(String input) {
    return part("request", "blob", "application/json", input);
  }

  Part types(String input) {
    return part("documentTypes", "blob", "application/json", input);
  }

  Part png(String name) {
    return new Part(
        name,
        "horse.png",
        "image/png",
        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0});
  }

  Part pdf() {
    return part("documents", "proof.pdf", "application/pdf", "%PDF-1.7\nexample");
  }

  HttpResponse<String> send(String method, String path, String token, Part... parts)
      throws Exception {
    String boundary = "TestBoundary" + UUID.randomUUID();
    var out = new java.io.ByteArrayOutputStream();
    for (Part p : parts) {
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + p.name()
                  + "\"; filename=\""
                  + p.filename()
                  + "\"\r\nContent-Type: "
                  + p.type()
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(p.data());
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var builder =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/horse-owner/horse-registration-requests"
                        + path))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(
        builder.method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path, String token) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/horse-owner/horse-registration-requests"
                        + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode created() throws Exception {
    var response =
        send(
            "POST",
            "",
            owner,
            request(
                "{\"horseName\":\"Thunder\",\"requestType\":\"LINK\",\"ownerNote\":\"note\",\"microchipNumber\":\"chip\",\"passportNumber\":\"pass\"}"),
            png("horseImage"),
            pdf(),
            png("documents"),
            types("[\"OWNERSHIP_PROOF\",\"HEALTH_DOCUMENT\"]"));
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    return json.readTree(response.body());
  }

  @Test
  void createsAndSupplementsWithRealMultipartAndPrivateUrls() throws Exception {
    var row = created();
    String id = row.path("id").asText();
    assertThat(row.path("request_type").asText()).isEqualTo("LINK");
    assertThat(row.path("owner_note").asText()).isEqualTo("note");
    assertThat(row.path("microchip_number").asText()).isEqualTo("chip");
    assertThat(row.path("passport_number").asText()).isEqualTo("pass");
    assertThat(row.path("horse_image_filename").asText()).isEqualTo("horse.png");
    assertThat(row.path("horseImageUrl").asText()).contains("/object/sign/");
    assertThat(row.path("documents").size()).isEqualTo(2);
    assertThat(row.path("documents").get(0).path("url").asText()).contains("token=");
    assertThat(row.path("history").size()).isEqualTo(1);
    assertThat(objects).hasSize(3);
    var history =
        (com.fasterxml.jackson.databind.node.ArrayNode)
            json.readTree(
                db.queryForObject(
                    "SELECT history_json FROM horse_registration_requests WHERE id=?",
                    String.class,
                    id));
    history.addObject().put("action", "REVIEWED");
    db.update(
        "UPDATE horse_registration_requests SET status='NEEDS_INFORMATION',history_json=? WHERE"
            + " id=?",
        history.toString(),
        id);
    var patch =
        send(
            "PATCH",
            "/" + id,
            owner,
            request("{\"ownerNote\":\"supplement\"}"),
            pdf(),
            types("[\"HORSE_CERTIFICATE\"]"));
    assertThat(patch.statusCode()).withFailMessage(patch.body()).isEqualTo(200);
    var updated = json.readTree(patch.body());
    assertThat(updated.path("documents").size()).isEqualTo(3);
    assertThat(updated.path("history").size()).isEqualTo(3);
    assertThat(updated.path("status").asText()).isEqualTo("PENDING");
    assertThat(updated.path("revision_no").asInt()).isEqualTo(2);
    assertThat(updated.path("horse_name").asText()).isEqualTo("Thunder");
    assertThat(updated.path("owner_note").asText()).isEqualTo("supplement");
    assertThat(get("/" + id, owner).statusCode()).isEqualTo(200);
    assertThat(get("/" + id, other).statusCode()).isEqualTo(404);
    assertThat(send("PATCH", "/" + id, other, pdf(), types("[\"OWNERSHIP_PROOF\"]")).statusCode())
        .isEqualTo(404);
    assertThat(json.readTree(get("", other).body()).path("items").size()).isZero();
    db.update("UPDATE horse_registration_requests SET status='APPROVED' WHERE id=?", id);
    assertThat(send("PATCH", "/" + id, owner, pdf(), types("[\"OWNERSHIP_PROOF\"]")).statusCode())
        .isEqualTo(409);
  }

  @Test
  void rejectsInvalidFilesCountsAndPermissionsWithoutWritingObjects() throws Exception {
    var req = request("{\"horseName\":\"Thunder\",\"requestType\":\"LINK\"}");
    assertThat(send("POST", "", owner, req, pdf()).statusCode()).isEqualTo(400);
    assertThat(send("POST", "", owner, req, types("[\"OWNERSHIP_PROOF\"]")).statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", owner, req, pdf(), types("[\"BAD\"]")).statusCode()).isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    req,
                    png("horseImage"),
                    part("documents", "bad.pdf", "application/pdf", "not a PDF"),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    req,
                    new Part("horseImage", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]),
                    pdf(),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(413);
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    req,
                    new Part(
                        "documents",
                        "large.pdf",
                        "application/pdf",
                        new byte[10 * 1024 * 1024 + 1]),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(413);
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    req,
                    pdf(),
                    pdf(),
                    pdf(),
                    pdf(),
                    pdf(),
                    pdf(),
                    types("[\"OTHER\",\"OTHER\",\"OTHER\",\"OTHER\",\"OTHER\",\"OTHER\"]"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    req,
                    part("horseImage", "horse.jpg", "image/jpeg", "%PDF-1.7 fake"))
                .statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", owner, req, types("not json")).statusCode()).isEqualTo(400);
    assertThat(send("POST", "", staff, req, pdf(), types("[\"OWNERSHIP_PROOF\"]")).statusCode())
        .isEqualTo(403);
    assertThat(send("POST", "", null, req).statusCode()).isEqualTo(401);
    assertThat(objects).isEmpty();
  }

  @Test
  void swaggerDocumentsBothMediaTypesAndJsonParts() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var paths = json.readTree(response.body()).path("paths");
    var post =
        paths
            .path("/api/horse-owner/horse-registration-requests")
            .path("post")
            .path("requestBody")
            .path("content");
    assertThat(post.has("application/json")).isTrue();
    assertThat(post.has("multipart/form-data")).isTrue();
    assertThat(
            post.path("multipart/form-data")
                .path("encoding")
                .path("request")
                .path("contentType")
                .asText())
        .isEqualTo("application/json");
    var patch =
        paths
            .path("/api/horse-owner/horse-registration-requests/{id}")
            .path("patch")
            .path("requestBody")
            .path("content");
    assertThat(patch.has("application/json")).isTrue();
    assertThat(patch.has("multipart/form-data")).isTrue();
  }

  @Test
  void rollsBackOnStorageFailureAndRejectsPublicBucket() throws Exception {
    long before = db.queryForObject("SELECT count(*) FROM horse_registration_requests", Long.class);
    failUpload = true;
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    request("{\"horseName\":\"Thunder\",\"requestType\":\"LINK\"}"),
                    pdf(),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(502);
    failUpload = false;
    publicBucket = true;
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    request("{\"horseName\":\"Thunder\",\"requestType\":\"LINK\"}"),
                    pdf(),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(503);
    publicBucket = false;
    failSigning = true;
    assertThat(
            send(
                    "POST",
                    "",
                    owner,
                    request("{\"horseName\":\"Thunder\",\"requestType\":\"LINK\"}"),
                    pdf(),
                    types("[\"OWNERSHIP_PROOF\"]"))
                .statusCode())
        .isEqualTo(502);
    assertThat(db.queryForObject("SELECT count(*) FROM horse_registration_requests", Long.class))
        .isEqualTo(before);
    assertThat(objects).isEmpty();
  }
}
