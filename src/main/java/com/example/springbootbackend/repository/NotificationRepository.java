package com.example.springbootbackend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.springbootbackend.entity.Notification;

import jakarta.persistence.LockModeType;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    Optional<Notification> findByIdAndUserId(String id, Long userId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            select count(n) from Notification n
            where n.userId = :userId
              and coalesce(n.scheduledAt, n.createdAt) <= :now
              and n.read = false
            """)
    long countVisibleUnread(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Query("""
            select count(n) from Notification n
            where n.userId = :userId
              and coalesce(n.scheduledAt, n.createdAt) <= :now
              and (:unreadOnly = false or n.read = false)
            """)
    long countVisible(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("unreadOnly") boolean unreadOnly
    );

    @Query("""
            select n from Notification n
            where n.userId = :userId
              and coalesce(n.scheduledAt, n.createdAt) <= :now
              and (:unreadOnly = false or n.read = false)
            order by coalesce(n.scheduledAt, n.createdAt) desc, n.createdAt desc, n.id desc
            """)
    List<Notification> findVisible(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from Notification n
            where n.id = :id
              and n.userId = :userId
              and coalesce(n.scheduledAt, n.createdAt) <= :now
            """)
    Optional<Notification> findVisibleByIdForUpdate(
            @Param("id") String id,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
            update Notification n
            set n.read = true
            where n.userId = :userId
              and n.read = false
              and coalesce(n.scheduledAt, n.createdAt) <= :now
            """)
    int markAllVisibleAsRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
