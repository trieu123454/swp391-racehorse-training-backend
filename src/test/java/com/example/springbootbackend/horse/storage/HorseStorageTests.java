package com.example.springbootbackend.horse.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HorseStorageTests {
    @Test void uploadsBinaryRegistersPathAndSignsWithoutExposingSecret() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seenKey=new AtomicReference<String>();
        var seenBody=new AtomicReference<byte[]>();
        server.createContext("/storage/v1/object/",exchange->{
            seenKey.set(exchange.getRequestHeaders().getFirst("apikey"));
            seenBody.set(exchange.getRequestBody().readAllBytes());
            String body=exchange.getRequestURI().getPath().contains("/sign/") ? "{\"signedURL\":\"/object/sign/horse-images/horses/photo.png?token=test\"}" : "{\"Key\":\"uploaded\"}";
            byte[] response=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var db=mock(JdbcTemplate.class);
            var storage=new HorseStorage("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","horse-images",db,new ObjectMapper());
            byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
            var result=storage.upload(new MockMultipartFile("file","horse.png","image/png",png),7L);
            String path=(String)result.get("imagePath");
            assertThat(path).matches("horses/[a-f0-9-]{36}\\.png");
            assertThat(seenKey.get()).isEqualTo("test-secret");
            assertThat(seenBody.get()).isEqualTo(png);
            verify(db).update("INSERT INTO horse_image_uploads(object_path,uploaded_by) VALUES (?,?)",path,7L);
            assertThat(storage.signedUrl(path).get("url").toString()).contains("/storage/v1/object/sign/").doesNotContain("test-secret");
            assertThat(new String(seenBody.get(),StandardCharsets.UTF_8)).contains("300");
        } finally { server.stop(0); }
    }
    @Test void storageFailureDoesNotRegisterAnImage() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{ exchange.sendResponseHeaders(503,-1); exchange.close(); });
        server.start();
        try {
            var db=mock(JdbcTemplate.class);
            var storage=new HorseStorage("http://127.0.0.1:"+server.getAddress().getPort(),"test-secret","horse-images",db,new ObjectMapper());
            byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
            assertThatThrownBy(()->storage.upload(new MockMultipartFile("file","horse.png","image/png",png),7L)).hasMessageContaining("502");
            verifyNoInteractions(db);
        } finally { server.stop(0); }
    }
}
