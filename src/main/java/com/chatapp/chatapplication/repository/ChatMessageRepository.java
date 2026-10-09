package com.chatapp.chatapplication.repository;

import com.chatapp.chatapplication.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    Optional<ChatMessageEntity> findByMessageId(String messageId);

    List<ChatMessageEntity> findTop100ByRoomIdAndTypeOrderByCreatedAtAsc(String roomId, String type);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.type = 'PRIVATE_CHAT' AND " +
           "((LOWER(m.sender) = LOWER(:u1) AND LOWER(m.receiver) = LOWER(:u2)) OR " +
           " (LOWER(m.sender) = LOWER(:u2) AND LOWER(m.receiver) = LOWER(:u1))) " +
           "ORDER BY m.createdAt ASC")
    List<ChatMessageEntity> findPrivateHistory(@Param("u1") String u1, @Param("u2") String u2);
}
