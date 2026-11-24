package com.example.springbootbackend.service;

import com.example.springbootbackend.dto.request.ApiPage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated server-side change feed, including reminders becoming due in the future. */
@Service
public class NotificationStreamService {
  private final NotificationService notifications;
  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final AtomicLong revision = new AtomicLong();

  private static final class Client {
    final String email;
    final SseEmitter emitter;
    Map<String, Object> snapshot;

    Client(String email, SseEmitter emitter) {
      this.email = email;
      this.emitter = emitter;
    }
  }

  public NotificationStreamService(NotificationService notifications) {
    this.notifications = notifications;
  }

  public SseEmitter open(String email) {
    // Perform access validation before committing an event-stream response.
    var snapshot = notifications.list(email, false, new ApiPage(1, 1));
    var emitter = new SseEmitter(60_000L);
    String id = UUID.randomUUID().toString();
    var client = new Client(email, emitter);
    emitter.onCompletion(() -> clients.remove(id));
    emitter.onTimeout(
        () -> {
          clients.remove(id);
          emitter.complete();
        });
    emitter.onError(error -> clients.remove(id));
    clients.put(id, client);
    send(id, client, snapshot);
    return emitter;
  }

  @Scheduled(fixedDelay = 3000)
  public void checkDueNotifications() {
    if (clients.isEmpty()) return;
    Map<String, Map<String, Object>> snapshots = new HashMap<>();
    clients.forEach(
        (id, client) -> {
          try {
            var snapshot =
                snapshots.computeIfAbsent(
                    client.email, email -> notifications.list(email, false, new ApiPage(1, 1)));
            send(id, client, snapshot);
          } catch (RuntimeException error) {
            clients.remove(id);
            client.emitter.complete();
          }
        });
  }

  private void send(String id, Client client, Map<String, Object> snapshot) {
    // Count + unread + newest row detects inserts, read state, deletes and newly due reminders.
    if (snapshot.equals(client.snapshot)) return;
    try {
      client.emitter.send(
          SseEmitter.event()
              .name("notifications")
              .data(Map.of("revision", revision.incrementAndGet())));
      client.snapshot = snapshot;
    } catch (IOException | IllegalStateException error) {
      clients.remove(id);
      client.emitter.complete();
    }
  }
}
