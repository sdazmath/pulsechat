package com.chatapp.chatapplication.controller;

import com.chatapp.chatapplication.model.ChatMessage;
import com.chatapp.chatapplication.model.MessageType;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Controller
public class ChatController {
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final SimpMessagingTemplate messagingTemplate;

    private final List<String> onlineUsers = new ArrayList<>();

    private final Set<String> rooms = ConcurrentHashMap.newKeySet();

    private final Map<String, ChatMessage> messageCache = new ConcurrentHashMap<>();

    private final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();
    private final com.chatapp.chatapplication.service.ChatHistoryService historyService;

    public ChatController(SimpMessagingTemplate messagingTemplate, com.chatapp.chatapplication.service.ChatHistoryService historyService) {
        this.messagingTemplate = messagingTemplate;
        this.historyService = historyService;
        rooms.add("General");
        rooms.add("Java");
        rooms.add("Spring Boot");
    }

    @MessageMapping("/rooms")
    @SendTo("/topic/rooms")
    public ChatMessage getRooms() {
        return new ChatMessage("SYSTEM", String.join(", ", rooms), MessageType.ROOM_LIST);
    }

    @MessageMapping("/room/create")
    public void createRoom(ChatMessage message) {
        String roomName = message.getContent() != null ? message.getContent().trim() : "";
        if (roomName.matches("^[a-zA-Z0-9 _-]{2,30}$")) {
            rooms.add(roomName);
            ChatMessage roomMsg = new ChatMessage("SYSTEM", String.join(", ", rooms), MessageType.ROOM_LIST);
            messagingTemplate.convertAndSend("/topic/rooms", roomMsg);
        }
    }

    @MessageMapping("/chat")
    public void sendMessage(ChatMessage message) {
        if (message.getContent() != null && message.getContent().length() > MAX_MESSAGE_LENGTH) {
            message.setContent(message.getContent().substring(0, MAX_MESSAGE_LENGTH));
        }

        if (message.getRoomId() == null || message.getRoomId().trim().isEmpty()) {
            message.setRoomId("General");
        }

        message.setType(MessageType.CHAT);

        LocalDateTime now = LocalDateTime.now();
        message.setTime(now.format(timeFormatter));
        message.setDate(now.format(dateFormatter));

        if (message.getId() == null || message.getId().trim().isEmpty()) {
            message.setId(UUID.randomUUID().toString());
        }
        message.setStatus("SENT");

        // Cache message for reaction, edit, delete support
        messageCache.put(message.getId(), message);

        // Broadcast to specific room topic
        messagingTemplate.convertAndSend("/topic/room/" + message.getRoomId(), message);

        if (historyService != null) {
            historyService.saveMessage(message);
        }
    }

    @MessageMapping("/private")
    public void sendPrivateMessage(ChatMessage message) {
        if (message.getContent() != null && message.getContent().length() > MAX_MESSAGE_LENGTH) {
            message.setContent(message.getContent().substring(0, MAX_MESSAGE_LENGTH));
        }

        LocalDateTime now = LocalDateTime.now();

        if (message.getReceiver() == null ||
            !onlineUsers.stream().anyMatch(
                    user -> user.equalsIgnoreCase(message.getReceiver()))) {

            ChatMessage errorMessage = new ChatMessage(
                    "SYSTEM",
                    "Recipient is not online",
                    MessageType.PRIVATE_CHAT
            );
            errorMessage.setId(message.getId() != null ? message.getId() : UUID.randomUUID().toString());
            errorMessage.setTime(now.format(timeFormatter));
            errorMessage.setDate(now.format(dateFormatter));
            errorMessage.setStatus("FAILED");

            messagingTemplate.convertAndSend(
                    "/topic/private/" + message.getSender(),
                    errorMessage
            );

            return;
        }

        message.setType(MessageType.PRIVATE_CHAT);
        message.setTime(now.format(timeFormatter));
        message.setDate(now.format(dateFormatter));

        if (message.getId() == null || message.getId().trim().isEmpty()) {
            message.setId(UUID.randomUUID().toString());
        }
        message.setStatus("SENT");

        messageCache.put(message.getId(), message);

        // Deliver to receiver
        messagingTemplate.convertAndSend(
                "/topic/private/" + message.getReceiver(),
                message
        );

        // Deliver copy to sender with SENT status
        messagingTemplate.convertAndSend(
                "/topic/private/" + message.getSender(),
                message
        );

        if (historyService != null) {
            historyService.saveMessage(message);
        }
    }

    @MessageMapping("/ack")
    public void acknowledgeMessage(ChatMessage ackRequest) {
        if (ackRequest.getId() == null || ackRequest.getStatus() == null) {
            return;
        }

        String newStatus = ackRequest.getStatus().toUpperCase();
        ChatMessage target = messageCache.get(ackRequest.getId());
        if (target != null) {
            if ("READ".equalsIgnoreCase(newStatus)) {
                target.setStatus("READ");
            } else if ("DELIVERED".equalsIgnoreCase(newStatus) && !"READ".equalsIgnoreCase(target.getStatus())) {
                target.setStatus("DELIVERED");
            }
        }

        ChatMessage ackUpdate = new ChatMessage();
        ackUpdate.setId(ackRequest.getId());
        ackUpdate.setType(MessageType.STATUS_UPDATE);
        ackUpdate.setStatus(newStatus);
        ackUpdate.setSender(ackRequest.getSender());
        ackUpdate.setReceiver(ackRequest.getReceiver());
        ackUpdate.setRoomId(ackRequest.getRoomId());

        if (ackRequest.getReceiver() != null && !ackRequest.getReceiver().trim().isEmpty()) {
            messagingTemplate.convertAndSend("/topic/private/" + ackRequest.getReceiver(), ackUpdate);
        }
        if (ackRequest.getRoomId() != null && !ackRequest.getRoomId().trim().isEmpty()) {
            messagingTemplate.convertAndSend("/topic/room/" + ackRequest.getRoomId(), ackUpdate);
        }

        if (historyService != null && target != null) {
            historyService.updateMessageStatus(target.getId(), target.getStatus());
        }
    }

    @MessageMapping("/react")
    public void reactToMessage(ChatMessage reactionRequest) {
        if (reactionRequest.getId() == null || reactionRequest.getEmoji() == null || reactionRequest.getSender() == null) {
            return;
        }

        ChatMessage target = messageCache.get(reactionRequest.getId());
        if (target == null) {
            return;
        }

        Map<String, List<String>> reactions = target.getReactions();
        if (reactions == null) {
            reactions = new HashMap<>();
            target.setReactions(reactions);
        }

        List<String> users = reactions.computeIfAbsent(reactionRequest.getEmoji(), k -> new ArrayList<>());
        if (users.contains(reactionRequest.getSender())) {
            users.remove(reactionRequest.getSender());
            if (users.isEmpty()) {
                reactions.remove(reactionRequest.getEmoji());
            }
        } else {
            users.add(reactionRequest.getSender());
        }

        ChatMessage update = new ChatMessage();
        update.setId(target.getId());
        update.setType(MessageType.REACTION);
        update.setEmoji(reactionRequest.getEmoji());
        update.setSender(reactionRequest.getSender());
        update.setReactions(reactions);
        update.setRoomId(target.getRoomId());
        update.setReceiver(target.getReceiver());

        if (target.getType() == MessageType.PRIVATE_CHAT) {
            messagingTemplate.convertAndSend("/topic/private/" + target.getReceiver(), update);
            messagingTemplate.convertAndSend("/topic/private/" + target.getSender(), update);
        } else {
            messagingTemplate.convertAndSend("/topic/room/" + target.getRoomId(), update);
        }
    }

    @MessageMapping("/edit")
    public void editMessage(ChatMessage editRequest) {
        if (editRequest.getId() == null || editRequest.getContent() == null || editRequest.getSender() == null) {
            return;
        }

        ChatMessage target = messageCache.get(editRequest.getId());
        if (target == null || target.isDeleted()) {
            return;
        }

        if (!target.getSender().equalsIgnoreCase(editRequest.getSender())) {
            return;
        }

        String newContent = editRequest.getContent().trim();
        if (newContent.isEmpty()) {
            return;
        }

        if (newContent.length() > MAX_MESSAGE_LENGTH) {
            newContent = newContent.substring(0, MAX_MESSAGE_LENGTH);
        }

        target.setContent(newContent);
        target.setEdited(true);

        ChatMessage update = new ChatMessage();
        update.setId(target.getId());
        update.setType(MessageType.EDIT);
        update.setContent(newContent);
        update.setEdited(true);
        update.setSender(target.getSender());
        update.setRoomId(target.getRoomId());
        update.setReceiver(target.getReceiver());

        if (target.getType() == MessageType.PRIVATE_CHAT) {
            messagingTemplate.convertAndSend("/topic/private/" + target.getReceiver(), update);
            messagingTemplate.convertAndSend("/topic/private/" + target.getSender(), update);
        } else {
            messagingTemplate.convertAndSend("/topic/room/" + target.getRoomId(), update);
        }

        if (historyService != null) {
            historyService.updateMessageContent(target.getId(), newContent);
        }
    }

    @MessageMapping("/delete")
    public void deleteMessage(ChatMessage deleteRequest) {
        if (deleteRequest.getId() == null || deleteRequest.getSender() == null) {
            return;
        }

        ChatMessage target = messageCache.get(deleteRequest.getId());
        if (target == null) {
            return;
        }

        if (!target.getSender().equalsIgnoreCase(deleteRequest.getSender())) {
            return;
        }

        target.setDeleted(true);
        target.setContent("This message was deleted");

        ChatMessage update = new ChatMessage();
        update.setId(target.getId());
        update.setType(MessageType.DELETE);
        update.setContent("This message was deleted");
        update.setDeleted(true);
        update.setSender(target.getSender());
        update.setRoomId(target.getRoomId());
        update.setReceiver(target.getReceiver());

        if (target.getType() == MessageType.PRIVATE_CHAT) {
            messagingTemplate.convertAndSend("/topic/private/" + target.getReceiver(), update);
            messagingTemplate.convertAndSend("/topic/private/" + target.getSender(), update);
        } else {
            messagingTemplate.convertAndSend("/topic/room/" + target.getRoomId(), update);
        }

        if (historyService != null) {
            historyService.markMessageDeleted(target.getId());
        }
    }

    @MessageMapping("/typing")
    @SendTo("/topic/public")
    public ChatMessage typing(ChatMessage message) {
        message.setType(MessageType.TYPING);
        return message;
    }

    @MessageMapping("/join")
    @SendTo("/topic/public")
    public ChatMessage joinChat(
            ChatMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        if (message.getSender() == null || !message.getSender().matches("^[a-zA-Z0-9_-]{3,20}$")) {
            message.setType(MessageType.USERNAME_TAKEN);
            message.setContent("Username must be 3-20 characters long and contain only letters, numbers, underscores, or hyphens");
            return message;
        }

        if (onlineUsers.stream()
            .anyMatch(user -> user.equalsIgnoreCase(message.getSender()))) {
            message.setType(MessageType.USERNAME_TAKEN);
            message.setContent("Username is already taken");
            return message;
        }

        message.setType(MessageType.JOIN);

        onlineUsers.add(message.getSender());
        userSessions.put(
                message.getSender(),
                headerAccessor.getSessionId()
        );

        messagingTemplate.convertAndSend(
                "/topic/public",
                new ChatMessage(
                        "SYSTEM",
                        String.join(", ", onlineUsers),
                        MessageType.USERS
                )
        );

        // Send current room list
        messagingTemplate.convertAndSend(
                "/topic/rooms",
                new ChatMessage(
                        "SYSTEM",
                        String.join(", ", rooms),
                        MessageType.ROOM_LIST
                )
        );

        LocalDateTime now = LocalDateTime.now();
        message.setTime(now.format(timeFormatter));
        message.setDate(now.format(dateFormatter));
        if (message.getId() == null || message.getId().trim().isEmpty()) {
            message.setId(UUID.randomUUID().toString());
        }

        return message;
    }

    @MessageMapping("/leave")
    @SendTo("/topic/public")
    public ChatMessage leaveChat(ChatMessage message) {
        message.setType(MessageType.LEAVE);
        if (message.getContent() == null || message.getContent().trim().isEmpty()) {
            message.setContent(message.getSender() + " left the chat");
        }
        onlineUsers.remove(message.getSender());
        userSessions.remove(message.getSender());

        messagingTemplate.convertAndSend(
                "/topic/public",
                new ChatMessage(
                        "SYSTEM",
                        String.join(", ", onlineUsers),
                        MessageType.USERS
                )
        );

        LocalDateTime now = LocalDateTime.now();
        message.setTime(now.format(timeFormatter));
        message.setDate(now.format(dateFormatter));
        if (message.getId() == null || message.getId().trim().isEmpty()) {
            message.setId(UUID.randomUUID().toString());
        }

        return message;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        List<String> disconnectedUsers = new ArrayList<>();
        userSessions.forEach((username, storedSessionId) -> {
            if (storedSessionId.equals(sessionId)) {
                disconnectedUsers.add(username);
            }
        });

        for (String username : disconnectedUsers) {
            ChatMessage leaveMessage = new ChatMessage(
                    username,
                    username + " left the chat",
                    MessageType.LEAVE
            );

            LocalDateTime now = LocalDateTime.now();
            leaveMessage.setTime(now.format(timeFormatter));
            leaveMessage.setDate(now.format(dateFormatter));
            leaveMessage.setId(UUID.randomUUID().toString());

            messagingTemplate.convertAndSend(
                    "/topic/public",
                    leaveMessage
            );

            onlineUsers.remove(username);
            userSessions.remove(username);

            messagingTemplate.convertAndSend(
                    "/topic/public",
                    new ChatMessage(
                            "SYSTEM",
                            String.join(", ", onlineUsers),
                            MessageType.USERS
                    )
            );
        }
    }
}