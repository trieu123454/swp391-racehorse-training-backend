import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** Read-only diagnostics. Credentials and bearer tokens are never printed. */
class InspectHealthExams {
  public static void main(String[] args) throws Exception {
    var p = new Properties();
    try (var r = Files.newBufferedReader(Path.of("application-local.properties"))) {
      p.load(r);
    }
    String url = p.getProperty("spring.datasource.url", p.getProperty("SUPABASE_DB_URL"));
    url +=
        (url.contains("?") ? "&" : "?") + "connectTimeout=10&socketTimeout=20&prepareThreshold=0";
    try (var c =
        DriverManager.getConnection(
            url,
            p.getProperty("spring.datasource.username", p.getProperty("SUPABASE_DB_USER")),
            p.getProperty("spring.datasource.password", p.getProperty("SUPABASE_DB_PASSWORD")))) {
      c.setReadOnly(true);
      for (String sql :
          List.of(
              "SELECT table_name,column_name,data_type FROM information_schema.columns WHERE"
                  + " table_schema='public' AND table_name IN ('health_exams','health_exam_logs')"
                  + " ORDER BY table_name,ordinal_position",
              "SELECT version,description,success FROM flyway_schema_history WHERE version IN"
                  + " ('28.8','28.9','28.13','28.14','40','42') ORDER BY installed_rank",
              "SELECT relname,relrowsecurity FROM pg_class WHERE"
                  + " oid=to_regclass('public.health_exam_logs')",
              "SELECT count(*) AS exam_count FROM health_exams WHERE"
                  + " horse_id='cba1036b-b3ca-41d9-a71f-51b4fb36e014'",
              "SELECT count(*) AS all_exams FROM health_exams")) {
        try (var s = c.createStatement();
            var r = s.executeQuery(sql)) {
          while (r.next()) {
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
              System.out.print((i == 1 ? "" : " | ") + r.getString(i));
            System.out.println();
          }
        }
      }
      String email;
      long userId;
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT u.email,u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id WHERE"
                      + " r.role_name IN ('VETERINARIAN','HEAD_VETERINARIAN') AND"
                      + " u.status='APPROVED' AND u.deleted_at IS NULL AND"
                      + " u.must_change_password=FALSE LIMIT 1")) {
        if (!r.next()) throw new IllegalStateException("No eligible veterinarian account");
        email = r.getString(1);
        userId = r.getLong(2);
      }
      var b64 = Base64.getUrlEncoder().withoutPadding();
      long now = System.currentTimeMillis() / 1000;
      String payload =
          "{\"iss\":\"springboot-backend\",\"sub\":\""
              + email
              + "\",\"userId\":"
              + userId
              + ",\"iat\":"
              + now
              + ",\"exp\":"
              + (now + 300)
              + "}";
      String unsigned =
          b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))
              + "."
              + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              p.getProperty(
                      "app.jwt.secret",
                      p.getProperty("AUTH_JWT_SECRET", "dev-secret-change-me-dev-secret-change-me"))
                  .getBytes(StandardCharsets.UTF_8),
              "HmacSHA256"));
      String token =
          unsigned
              + "."
              + b64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
      var paths = new ArrayList<String>();
      paths.add("/api/horses/cba1036b-b3ca-41d9-a71f-51b4fb36e014/health-exams?page=1&limit=20");
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT e.id FROM health_exams e JOIN horses h ON h.id=e.horse_id WHERE"
                      + " h.deleted_at IS NULL ORDER BY CASE WHEN"
                      + " e.horse_id='cba1036b-b3ca-41d9-a71f-51b4fb36e014' THEN 0 ELSE 1"
                      + " END,e.exam_date DESC LIMIT 1")) {
        if (r.next()) {
          String id = r.getString(1).strip();
          paths.add("/api/health-exams/" + id);
          paths.add("/api/health-exams/" + id + "/logs?page=1&limit=20");
        } else
          System.out.println(
              "No existing health exams: live detail/log verification unavailable; use isolated"
                  + " integration tests.");
      }
      var http = HttpClient.newHttpClient();
      for (String path : paths) {
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:8080" + path))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println(path + " HTTP " + response.statusCode());
        // Only print error bodies, never patient records.
        if (response.statusCode() != 200) System.out.println(response.body());
        else if (path.contains("?")) {
          for (String key : List.of("data", "total", "page", "limit"))
            if (!response.body().contains("\"" + key + "\":"))
              throw new IllegalStateException("Missing response key: " + key);
          System.out.println("Verified keys: data, total, page, limit");
        }
      }
    }
  }
}
