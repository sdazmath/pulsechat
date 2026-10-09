package com.chatapp.chatapplication.controller;

import com.chatapp.chatapplication.model.ChatMessage;
import com.chatapp.chatapplication.model.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatControllerTest {

    private SimpMessagingTemplate messagingTemplate;
    private com.chatapp.chatapplication.service.ChatHistoryService historyService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        historyService = mock(com.chatapp.chatapplication.service.ChatHistoryService.class);
        controller = new ChatController(messagingTemplate, historyService);
    }

    @Test
    void testSendMessageSetsDeliveryStatusAndTimestamps() {
        ChatMessage message = new ChatMessage("Alice", "Hello World", MessageType.CHAT);
        message.setId("msg-123");
        message.setRoomId("General");

        controller.sendMessage(message);

        assertEquals("msg-123", message.getId());
        assertEquals("SENT", message.getStatus());
        assertNotNull(message.getTime());
        assertNotNull(message.getDate());
        assertTrue(message.getTime().matches("\\d{2}:\\d{2}"));
        assertTrue(message.getDate().matches("\\d{4}-\\d{2}-\\d{2}"));

        verify(messagingTemplate).convertAndSend(eq("/topic/room/General"), eq(message));
    }

    @Test
    void testAcknowledgeMessageDeliveredAndRead() {
        ChatMessage message = new ChatMessage("Alice", "Hello ACK", MessageType.CHAT);
        message.setId("msg-ack-1");
        message.setRoomId("General");
        controller.sendMessage(message);

        ChatMessage ackDelivered = new ChatMessage();
        ackDelivered.setId("msg-ack-1");
        ackDelivered.setStatus("DELIVERED");
        ackDelivered.setSender("Bob");
        ackDelivered.setRoomId("General");
        controller.acknowledgeMessage(ackDelivered);

        ChatMessage ackRead = new ChatMessage();
        ackRead.setId("msg-ack-1");
        ackRead.setStatus("READ");
        ackRead.setSender("Bob");
        ackRead.setRoomId("General");
        controller.acknowledgeMessage(ackRead);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/room/General"), captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> MessageType.STATUS_UPDATE.equals(m.getType()) && "READ".equals(m.getStatus())));
    }

    @Test
    void testSendMessageLimitsContentTo500Characters() {
        String longContent = "A".repeat(600);
        ChatMessage message = new ChatMessage("Alice", longContent, MessageType.CHAT);

        controller.sendMessage(message);

        assertEquals(500, message.getContent().length());
    }

    @Test
    void testJoinChatAndDuplicatePrevention() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session-1");

        ChatMessage joinMsg1 = new ChatMessage("Alice", "joining", MessageType.JOIN);
        ChatMessage res1 = controller.joinChat(joinMsg1, headerAccessor);
        assertEquals(MessageType.JOIN, res1.getType());

        // Try joining with same username in different casing
        ChatMessage joinMsg2 = new ChatMessage("alice", "joining again", MessageType.JOIN);
        ChatMessage res2 = controller.joinChat(joinMsg2, headerAccessor);
        assertEquals(MessageType.USERNAME_TAKEN, res2.getType());
    }

    @Test
    void testSendPrivateMessageWhenRecipientIsOffline() {
        ChatMessage privateMsg = new ChatMessage("Alice", "Hey Bob", MessageType.PRIVATE_CHAT);
        privateMsg.setReceiver("Bob");
        privateMsg.setId("msg-priv-1");

        controller.sendPrivateMessage(privateMsg);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/private/Alice"), captor.capture());

        ChatMessage sent = captor.getValue();
        assertEquals("SYSTEM", sent.getSender());
        assertEquals("Recipient is not online", sent.getContent());
        assertEquals("FAILED", sent.getStatus());
    }

    @Test
    void testSendPrivateMessageWhenRecipientIsOnline() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session-bob");

        ChatMessage bobJoin = new ChatMessage("Bob", "joining", MessageType.JOIN);
        controller.joinChat(bobJoin, headerAccessor);

        ChatMessage privateMsg = new ChatMessage("Alice", "Secret message", MessageType.PRIVATE_CHAT);
        privateMsg.setReceiver("Bob");
        privateMsg.setId("msg-priv-1");

        controller.sendPrivateMessage(privateMsg);

        // Verify sent to Bob (recipient)
        verify(messagingTemplate).convertAndSend(eq("/topic/private/Bob"), any(ChatMessage.class));

        // Verify delivery confirmation sent to Alice (sender)
        verify(messagingTemplate).convertAndSend(eq("/topic/private/Alice"), any(ChatMessage.class));
    }

    @Test
    void testUsernameValidationRules() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session-test");

        // Too short (< 3 chars)
        ChatMessage shortUser = new ChatMessage("Al", "joining", MessageType.JOIN);
        ChatMessage resShort = controller.joinChat(shortUser, headerAccessor);
        assertEquals(MessageType.USERNAME_TAKEN, resShort.getType());
        assertTrue(resShort.getContent().contains("3-20 characters"));

        // Too long (> 20 chars)
        ChatMessage longUser = new ChatMessage("A".repeat(21), "joining", MessageType.JOIN);
        ChatMessage resLong = controller.joinChat(longUser, headerAccessor);
        assertEquals(MessageType.USERNAME_TAKEN, resLong.getType());
        assertTrue(resLong.getContent().contains("3-20 characters"));

        // Invalid characters (spaces, special symbols)
        ChatMessage invalidUser = new ChatMessage("Alice Walker!", "joining", MessageType.JOIN);
        ChatMessage resInvalid = controller.joinChat(invalidUser, headerAccessor);
        assertEquals(MessageType.USERNAME_TAKEN, resInvalid.getType());

        // Valid username with underscores and hyphens
        ChatMessage validUser = new ChatMessage("user_123-cool", "joining", MessageType.JOIN);
        ChatMessage resValid = controller.joinChat(validUser, headerAccessor);
        assertEquals(MessageType.JOIN, resValid.getType());
    }

    @Test
    void testLeaveChatRemovesUser() {
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session-alice");

        ChatMessage joinMsg = new ChatMessage("Alice", "joining", MessageType.JOIN);
        controller.joinChat(joinMsg, headerAccessor);

        ChatMessage leaveMsg = new ChatMessage("Alice", "", MessageType.LEAVE);
        ChatMessage result = controller.leaveChat(leaveMsg);

        assertEquals(MessageType.LEAVE, result.getType());
        assertEquals("Alice left the chat", result.getContent());
    }

    @Test
    void testRoomCreationAndListing() {
        ChatMessage createReq = new ChatMessage();
        createReq.setContent("DevOps");

        controller.createRoom(createReq);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/rooms"), captor.capture());

        ChatMessage roomList = captor.getValue();
        assertEquals(MessageType.ROOM_LIST, roomList.getType());
        assertTrue(roomList.getContent().contains("DevOps"));
        assertTrue(roomList.getContent().contains("General"));
    }

    @Test
    void testMessageReactionsToggle() {
        ChatMessage message = new ChatMessage("Alice", "Great job!", MessageType.CHAT);
        message.setId("msg-reaction-1");
        message.setRoomId("General");
        controller.sendMessage(message);

        // Bob reacts with 👍
        ChatMessage reactReq = new ChatMessage();
        reactReq.setId("msg-reaction-1");
        reactReq.setSender("Bob");
        reactReq.setEmoji("👍");

        controller.reactToMessage(reactReq);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/room/General"), captor.capture());

        ChatMessage reactionBroadcast = captor.getValue();
        assertEquals(MessageType.REACTION, reactionBroadcast.getType());
        assertNotNull(reactionBroadcast.getReactions());
        assertTrue(reactionBroadcast.getReactions().containsKey("👍"));
        assertEquals(List.of("Bob"), reactionBroadcast.getReactions().get("👍"));

        // Bob clicks again to toggle off
        controller.reactToMessage(reactReq);
        verify(messagingTemplate, atLeast(2)).convertAndSend(eq("/topic/room/General"), captor.capture());
        ChatMessage toggled = captor.getValue();
        assertFalse(toggled.getReactions().containsKey("👍"));
    }

    @Test
    void testMessageEdit() {
        ChatMessage message = new ChatMessage("Alice", "Original text", MessageType.CHAT);
        message.setId("msg-edit-1");
        message.setRoomId("General");
        controller.sendMessage(message);

        // Unauthorized edit attempt by Bob should be ignored
        ChatMessage unauthorizedEdit = new ChatMessage();
        unauthorizedEdit.setId("msg-edit-1");
        unauthorizedEdit.setSender("Bob");
        unauthorizedEdit.setContent("Hacked text");
        controller.editMessage(unauthorizedEdit);

        // Valid edit by Alice
        ChatMessage validEdit = new ChatMessage();
        validEdit.setId("msg-edit-1");
        validEdit.setSender("Alice");
        validEdit.setContent("Updated text");
        controller.editMessage(validEdit);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/room/General"), captor.capture());

        ChatMessage editBroadcast = captor.getValue();
        assertEquals(MessageType.EDIT, editBroadcast.getType());
        assertEquals("Updated text", editBroadcast.getContent());
        assertTrue(editBroadcast.isEdited());
    }

    @Test
    void testMessageDelete() {
        ChatMessage message = new ChatMessage("Alice", "To be deleted", MessageType.CHAT);
        message.setId("msg-del-1");
        message.setRoomId("General");
        controller.sendMessage(message);

        // Valid delete by Alice
        ChatMessage deleteReq = new ChatMessage();
        deleteReq.setId("msg-del-1");
        deleteReq.setSender("Alice");
        controller.deleteMessage(deleteReq);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/room/General"), captor.capture());

        ChatMessage delBroadcast = captor.getValue();
        assertEquals(MessageType.DELETE, delBroadcast.getType());
        assertTrue(delBroadcast.isDeleted());
        assertEquals("This message was deleted", delBroadcast.getContent());
    }
}
