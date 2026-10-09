package com.chatapp.chatapplication.service;

import com.chatapp.chatapplication.dto.AuthResponse;
import com.chatapp.chatapplication.dto.LoginRequest;
import com.chatapp.chatapplication.dto.RegisterRequest;
import com.chatapp.chatapplication.entity.UserAccount;
import com.chatapp.chatapplication.repository.UserAccountRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserAccountRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            return new AuthResponse(false, "Username is already taken");
        }

        String hash = passwordEncoder.encode(request.getPassword());
        UserAccount account = new UserAccount(request.getUsername(), hash, request.getEmail());
        if (request.getBio() != null) {
            account.setBio(request.getBio());
        }

        userRepository.save(account);

        return new AuthResponse(true, "Registration successful", account.getUsername(), account.getBio(), account.getPreferredTheme());
    }

    public AuthResponse login(LoginRequest request) {
        Optional<UserAccount> userOpt = userRepository.findByUsernameIgnoreCase(request.getUsername());
        if (userOpt.isEmpty()) {
            return new AuthResponse(false, "Invalid username or password");
        }

        UserAccount user = userOpt.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return new AuthResponse(false, "Invalid username or password");
        }

        return new AuthResponse(true, "Login successful", user.getUsername(), user.getBio(), user.getPreferredTheme());
    }

    public Optional<UserAccount> getProfile(String username) {
        return userRepository.findByUsernameIgnoreCase(username);
    }

    public boolean updateProfile(String username, String bio, String preferredTheme) {
        Optional<UserAccount> userOpt = userRepository.findByUsernameIgnoreCase(username);
        if (userOpt.isEmpty()) return false;

        UserAccount user = userOpt.get();
        if (bio != null) user.setBio(bio);
        if (preferredTheme != null) user.setPreferredTheme(preferredTheme);

        userRepository.save(user);
        return true;
    }
}
