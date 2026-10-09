package com.chatapp.chatapplication.service;

import com.chatapp.chatapplication.entity.ChatMessageEntity;
import com.chatapp.chatapplication.model.ChatMessage;
import com.chatapp.chatapplication.model.MessageType;
import com.chatapp.chatapplication.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ChatHistoryService {

    private final ChatMessageRepository messageRepository;

    public ChatHistoryService(ChatMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public void saveMessage(ChatMessage message) {
        if (message == null || message.getContent() == null || message.getContent().trim().isEmpty()) {
            return;
        }

        // Feature 48: Input Sanitization / HTML injection prevention
        String sanitizedContent = HtmlUtils.htmlEscape(message.getContent().trim());

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(message.getId());
        entity.setSender(message.getSender());
        entity.setReceiver(message.getReceiver());
        entity.setRoomId(message.getRoomId() != null ? message.getRoomId() : "General");
        entity.setContent(sanitizedContent);
        entity.setType(message.getType() != null ? message.getType().name() : "CHAT");
        entity.setTime(message.getTime());
        entity.setDate(message.getDate());
        entity.setStatus(message.getStatus());
        entity.setReplyToId(message.getReplyToId());
        entity.setReplyToSender(message.getReplyToSender());
        entity.setReplyToContent(message.getReplyToContent() != null ? HtmlUtils.htmlEscape(message.getReplyToContent()) : null);

        messageRepository.save(entity);
    }

    public List<ChatMessage> getRoomHistory(String roomId) {
        String effectiveRoom = (roomId == null || roomId.trim().isEmpty()) ? "General" : roomId;
        List<ChatMessageEntity> entities = messageRepository.findTop100ByRoomIdAndTypeOrderByCreatedAtAsc(effectiveRoom, "CHAT");
        return entities.stream().map(this::toDto).collect(Collectors.toList());
    }

    public List<ChatMessage> getPrivateHistory(String user1, String user2) {
        List<ChatMessageEntity> entities = messageRepository.findPrivateHistory(user1, user2);
        return entities.stream().map(this::toDto).collect(Collectors.toList());
    }

    public void updateMessageContent(String messageId, String newContent) {
        Optional<ChatMessageEntity> opt = messageRepository.findByMessageId(messageId);
        if (opt.isPresent()) {
            ChatMessageEntity entity = opt.get();
            entity.setContent(HtmlUtils.htmlEscape(newContent));
            entity.setEdited(true);
            messageRepository.save(entity);
        }
    }

    public void markMessageDeleted(String messageId) {
        Optional<ChatMessageEntity> opt = messageRepository.findByMessageId(messageId);
        if (opt.isPresent()) {
            ChatMessageEntity entity = opt.get();
            entity.setDeleted(true);
            entity.setContent("This message was deleted");
            messageRepository.save(entity);
        }
    }

    public void updateMessageStatus(String messageId, String newStatus) {
        if (messageId == null || newStatus == null) return;
        Optional<ChatMessageEntity> opt = messageRepository.findByMessageId(messageId);
        if (opt.isPresent()) {
            ChatMessageEntity entity = opt.get();
            entity.setStatus(newStatus);
            messageRepository.save(entity);
        }
    }

    private ChatMessage toDto(ChatMessageEntity entity) {
        ChatMessage dto = new ChatMessage();
        dto.setId(entity.getMessageId());
        dto.setSender(entity.getSender());
        dto.setReceiver(entity.getReceiver());
        dto.setRoomId(entity.getRoomId());
        dto.setContent(entity.getContent());
        try {
            dto.setType(MessageType.valueOf(entity.getType()));
        } catch (Exception e) {
            dto.setType(MessageType.CHAT);
        }
        dto.setTime(entity.getTime());
        dto.setDate(entity.getDate());
        dto.setStatus(entity.getStatus());
        dto.setReplyToId(entity.getReplyToId());
        dto.setReplyToSender(entity.getReplyToSender());
        dto.setReplyToContent(entity.getReplyToContent());
        dto.setEdited(entity.isEdited());
        dto.setDeleted(entity.isDeleted());
        return dto;
    }
}
