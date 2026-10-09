package com.chatapp.chatapplication.controller;

import com.chatapp.chatapplication.model.ChatMessage;
import com.chatapp.chatapplication.service.ChatHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class ChatHistoryController {

    private final ChatHistoryService historyService;

    public ChatHistoryController(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<ChatMessage>> getRoomHistory(@PathVariable String roomId) {
        return ResponseEntity.ok(historyService.getRoomHistory(roomId));
    }

    @GetMapping("/private")
    public ResponseEntity<List<ChatMessage>> getPrivateHistory(
            @RequestParam String user1,
            @RequestParam String user2) {
        return ResponseEntity.ok(historyService.getPrivateHistory(user1, user2));
    }
}
