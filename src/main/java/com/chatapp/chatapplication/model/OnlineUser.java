package com.chatapp.chatapplication.model;

public class OnlineUser {

    private String username;

    public OnlineUser() {
    }

    public OnlineUser(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}