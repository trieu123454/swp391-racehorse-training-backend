package com.example.springbootbackend.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CloudinaryHorseImagesTests {
    @Test
    void resolvesLegacyRegistrationPdfUsingItsDocumentFolder() throws Exception {
        String id = "e298ed64-ae0f-3e0c-8a1d-cfab1a8b910e";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext(
                "/",
                exchange -> {
                    assertThat(exchange.getRequestURI().getPath())
                            .isEqualTo("/demo/resources/image/upload/horse-documents/" + id);
                    byte[] body = "{\"type\":\"upload\",\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v123/horse-documents/proof.pdf\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var images = new CloudinaryHorseImages(
                    "demo",
                    "key",
                    "secret",
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertThat(images.resolve("horse-documents/cloudinary/" + id + ".pdf"))
                    .containsEntry(
                            "url", "https://res.cloudinary.com/demo/image/upload/v123/horse-documents/proof.pdf");
            status.set(404);
            assertThat(images.resolve("horse-documents/cloudinary/" + id + ".pdf"))
                    .containsEntry("available", false)
                    .containsEntry("unavailableReason", "FILE_NOT_FOUND")
                    .doesNotContainKey("url");
            status.set(500);
            assertThatThrownBy(() -> images.resolve("horse-documents/cloudinary/" + id + ".pdf"))
                    .hasMessageContaining("502");
            assertThatThrownBy(() -> images.resolve("horse-documents/cloudinary/../../secret.pdf"))
                    .hasMessageContaining("400");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesActualPublicIdAndFormatAndDistinguishesMissingFromProviderFailure()
            throws Exception {
        String id = "e298ed64-ae0f-3e0c-8a1d-cfab1a8b910e";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext(
                "/",
                exchange -> {
                    assertThat(exchange.getRequestURI().getPath())
                            .isEqualTo("/demo/resources/image/upload/horses/" + id);
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
                    byte[] response = "{\"type\":\"upload\",\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v123/horses/actual.png\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var images = new CloudinaryHorseImages(
                    "demo",
                    "key",
                    "secret",
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertThat(images.resolve("horses/cloudinary/" + id + ".jpg"))
                    .containsEntry("hasImage", true)
                    .containsEntry(
                            "url",
                            "https://res.cloudinary.com/demo/image/upload/f_auto,q_auto:good,w_720,c_limit/v123/horses/actual.png")
                    .containsEntry("expiresIn", 0);
            status.set(404);
            assertThatThrownBy(() -> images.resolve("horses/cloudinary/" + id + ".jpg"))
                    .hasMessageContaining("404");
            status.set(500);
            assertThatThrownBy(() -> images.resolve("horses/cloudinary/" + id + ".jpg"))
                    .hasMessageContaining("502");
            assertThatThrownBy(() -> images.resolve("horses/cloudinary/../../secret.jpg"))
                    .hasMessageContaining("400");
        } finally {
            server.stop(0);
        }
    }
}
