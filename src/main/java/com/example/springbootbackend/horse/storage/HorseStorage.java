package com.example.springbootbackend.horse.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class HorseStorage {
    private static final Logger log=LoggerFactory.getLogger(HorseStorage.class);
    private final String url,key,bucket;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public HorseStorage(@Value("${app.supabase.url:}") String url,@Value("${app.supabase.secret-key:}") String key,
                        @Value("${app.supabase.bucket:horse-images}") String bucket,JdbcTemplate db,ObjectMapper json) {
        this.url=url.replaceAll("/+$",""); this.key=key; this.bucket=bucket; this.db=db; this.json=json;
    }
    public Map<String,Object> upload(MultipartFile file,long actor) {
        if(file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST,"Ảnh không được rỗng");
        if(file.getSize()>5*1024*1024) throw new ResponseStatusException(PAYLOAD_TOO_LARGE,"Ảnh không được vượt quá 5 MB");
        try {
            byte[] bytes=file.getBytes();
            String type=detect(bytes);
            String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
            String extension=switch(type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".webp"; };
            if(!type.equals(file.getContentType()) || !(name.endsWith(extension) || (extension.equals(".jpg") && name.endsWith(".jpeg"))))
                throw new ResponseStatusException(BAD_REQUEST,"Chỉ chấp nhận ảnh JPG, PNG, WebP đúng định dạng");
            String path="horses/"+UUID.randomUUID()+extension;
            send("/object/"+bucket+"/"+path,type,bytes);
            db.update("INSERT INTO horse_image_uploads(object_path,uploaded_by) VALUES (?,?)",path,actor);
            return Map.of("imagePath",path);
        } catch(java.io.IOException e) { throw new ResponseStatusException(BAD_REQUEST,"Không đọc được ảnh"); }
    }
    static String detect(byte[] b) {
        if(b.length>=12) {
            if((b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
            if(Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
            if(new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        }
        throw new ResponseStatusException(BAD_REQUEST,"Nội dung file không phải ảnh JPG, PNG hoặc WebP");
    }
    public Map<String,Object> signedUrl(String path) {
        if(path==null) return Map.of("hasImage",false);
        if(!path.matches("horses/[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) throw new ResponseStatusException(BAD_REQUEST,"Đường dẫn ảnh không hợp lệ");
        String response=send("/object/sign/"+bucket+"/"+path,"application/json","{\"expiresIn\":300}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            String signed=json.readTree(response).path("signedURL").asText();
            if(!signed.startsWith("/object/sign/")) throw new IllegalArgumentException();
            return Map.of("hasImage",true,"url",url+"/storage/v1"+signed,"expiresIn",300);
        } catch(Exception e) { throw new ResponseStatusException(BAD_GATEWAY,"Không lấy được link ảnh"); }
    }
    private String send(String path,String type,byte[] bytes) {
        if(url.isBlank() || key.isBlank() || !bucket.matches("[a-zA-Z0-9_-]+")) throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Storage chưa được cấu hình");
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/storage/v1"+path)).timeout(Duration.ofSeconds(30))
                .header("apikey",key).header("Content-Type",type).header("x-upsert","false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var result=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()<200 || result.statusCode()>=300) throw new ResponseStatusException(BAD_GATEWAY,"Storage tạm thời không khả dụng, vui lòng thử lại");
            return result.body();
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Upload bị gián đoạn"); }
          catch(java.io.IOException e) {
              log.warn("Storage connection failed (host={}): {}",URI.create(url).getHost(),e.toString());
              for(Throwable cause=e.getCause();cause!=null;cause=cause.getCause())
                  log.warn("Storage connection cause: {}",cause.toString());
              throw new ResponseStatusException(BAD_GATEWAY,"Không kết nối được Storage",e);
          }
    }
}
