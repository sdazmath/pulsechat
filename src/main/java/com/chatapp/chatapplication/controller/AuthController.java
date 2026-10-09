package com.chatapp.chatapplication.controller;

import com.chatapp.chatapplication.dto.AuthResponse;
import com.chatapp.chatapplication.dto.LoginRequest;
import com.chatapp.chatapplication.dto.RegisterRequest;
import com.chatapp.chatapplication.entity.UserAccount;
import com.chatapp.chatapplication.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpSession session) {
        AuthResponse response = authService.register(request);
        if (response.isSuccess()) {
            session.setAttribute("username", response.getUsername());
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        AuthResponse response = authService.login(request);
        if (response.isSuccess()) {
            session.setAttribute("username", response.getUsername());
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        Optional<UserAccount> profile = authService.getProfile(username);
        return profile.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    @GetMapping("/profile/{username}")
    public ResponseEntity<?> getProfile(@PathVariable String username) {
        Optional<UserAccount> profile = authService.getProfile(username);
        return profile.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).build());
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        boolean ok = authService.updateProfile(username, body.get("bio"), body.get("preferredTheme"));
        if (ok) {
            return ResponseEntity.ok(Map.of("message", "Profile updated"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
    }
}
