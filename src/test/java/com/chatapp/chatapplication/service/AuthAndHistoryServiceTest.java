package com.chatapp.chatapplication.service;

import com.chatapp.chatapplication.dto.AuthResponse;
import com.chatapp.chatapplication.dto.LoginRequest;
import com.chatapp.chatapplication.dto.RegisterRequest;
import com.chatapp.chatapplication.entity.ChatMessageEntity;
import com.chatapp.chatapplication.entity.UserAccount;
import com.chatapp.chatapplication.model.ChatMessage;
import com.chatapp.chatapplication.model.MessageType;
import com.chatapp.chatapplication.repository.ChatMessageRepository;
import com.chatapp.chatapplication.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthAndHistoryServiceTest {

    private UserAccountRepository userAccountRepository;
    private ChatMessageRepository chatMessageRepository;
    private AuthService authService;
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        authService = new AuthService(userAccountRepository);
        chatHistoryService = new ChatHistoryService(chatMessageRepository);
    }

    @Test
    void testRegisterHashesPasswordAndSaves() {
        RegisterRequest req = new RegisterRequest("azmath_user", "plainPassword123", "azmath@test.com");
        when(userAccountRepository.existsByUsernameIgnoreCase("azmath_user")).thenReturn(false);

        AuthResponse res = authService.register(req);

        assertTrue(res.isSuccess());
        assertEquals("azmath_user", res.getUsername());

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());

        UserAccount saved = captor.getValue();
        assertNotEquals("plainPassword123", saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("$2a$") || saved.getPasswordHash().startsWith("$2b$"));
    }

    @Test
    void testLoginValidAndInvalidPassword() {
        RegisterRequest req = new RegisterRequest("test_user", "mypassword", "test@test.com");
        when(userAccountRepository.existsByUsernameIgnoreCase("test_user")).thenReturn(false);
        authService.register(req);

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccount saved = captor.getValue();

        when(userAccountRepository.findByUsernameIgnoreCase("test_user")).thenReturn(Optional.of(saved));

        // Correct password
        AuthResponse ok = authService.login(new LoginRequest("test_user", "mypassword"));
        assertTrue(ok.isSuccess());

        // Incorrect password
        AuthResponse fail = authService.login(new LoginRequest("test_user", "wrongpassword"));
        assertFalse(fail.isSuccess());
    }

    @Test
    void testChatHistoryServiceSanitizesHtml() {
        ChatMessage msg = new ChatMessage("Alice", "<script>alert('xss')</script>Hello", MessageType.CHAT);
        msg.setId("msg-xss");

        chatHistoryService.saveMessage(msg);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageRepository).save(captor.capture());

        ChatMessageEntity saved = captor.getValue();
        assertFalse(saved.getContent().contains("<script>"));
        assertTrue(saved.getContent().contains("&lt;script&gt;"));
    }
}
