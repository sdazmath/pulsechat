let stompClient = null;
let username = "";
let currentUserProfile = null;
let hasJoined = false;
let lastRenderedDate = null;

// Reconnection State (Feature 49)
let reconnectTimer = null;
let reconnectAttempts = 0;
let isReconnecting = false;

// Rooms & Messaging State
let currentRoom = "General";
let availableRooms = ["General", "Java", "Spring Boot"];
let activePrivateRecipient = null; // null for room mode, or username string for private chat mode
let actionContext = null; // { type: 'reply' | 'edit', messageId: string, sender: string, content: string }
const messageStore = new Map(); // messageId -> ChatMessage object
const renderedMessageIds = new Set(); // tracks message IDs rendered in current DOM view
let currentRoomSubscription = null;

// Avatar Palette
const AVATAR_COLORS = [
    '#e53935', '#d81b60', '#8e24aa', '#5e35b1',
    '#3949ab', '#1e88e5', '#039be5', '#00acc1',
    '#00897b', '#43a047', '#7cb342', '#fb8c00',
    '#f4511e', '#6d4c41', '#546e7a'
];

function getAvatarColor(name) {
    if (!name) return '#546e7a';
    const clean = name.replace(/^\[.*?\]\s*/, '').trim();
    let hash = 0;
    for (let i = 0; i < clean.length; i++) {
        hash = clean.charCodeAt(i) + ((hash << 5) - hash);
    }
    const index = Math.abs(hash) % AVATAR_COLORS.length;
    return AVATAR_COLORS[index];
}

function getAvatarInitials(name) {
    if (!name) return '?';
    const clean = name.replace(/^\[.*?\]\s*/, '').replace(/[^a-zA-Z0-9_\-\s]/g, '').trim();
    if (!clean) return '?';
    const words = clean.split(/[\s_\-]+/);
    if (words.length >= 2 && words[0] && words[1]) {
        return (words[0][0] + words[1][0]).toUpperCase();
    }
    return clean.substring(0, Math.min(2, clean.length)).toUpperCase();
}

function createAvatarElement(name, sizeClass = "avatar-sm") {
    const avatar = document.createElement("span");
    avatar.className = `avatar-circle ${sizeClass}`;
    avatar.style.backgroundColor = getAvatarColor(name);
    avatar.textContent = getAvatarInitials(name);
    avatar.title = name;
    return avatar;
}

function getLocalDateString(date = new Date()) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function getLocalTimeString(date = new Date()) {
    const hours = String(date.getHours()).padStart(2, '0');
    const mins = String(date.getMinutes()).padStart(2, '0');
    return `${hours}:${mins}`;
}

function formatDateLabel(dateString) {
    if (!dateString) return "Today";

    const todayStr = getLocalDateString(new Date());
    if (dateString === todayStr) return "Today";

    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    if (dateString === getLocalDateString(yesterday)) return "Yesterday";

    const parts = dateString.split("-");
    if (parts.length === 3) {
        const d = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
        return d.toLocaleDateString(undefined, {
            weekday: 'short',
            month: 'short',
            day: 'numeric',
            year: 'numeric'
        });
    }
    return dateString;
}

function ensureDateDivider(dateString) {
    const effectiveDate = dateString || getLocalDateString();
    if (effectiveDate !== lastRenderedDate) {
        const messagesContainer = document.getElementById("messages");
        const divider = document.createElement("div");
        divider.className = "date-divider";
        divider.innerHTML = `<span>${formatDateLabel(effectiveDate)}</span>`;
        messagesContainer.appendChild(divider);
        lastRenderedDate = effectiveDate;
    }
}

// Character counter helper
function updateCharCounter(inputId, counterId) {
    const input = document.getElementById(inputId);
    const counter = document.getElementById(counterId);
    if (!input || !counter) return;

    const len = input.value.length;
    counter.textContent = `${len} / 500`;

    if (len >= 500) {
        counter.className = "char-counter limit";
    } else if (len >= 450) {
        counter.className = "char-counter warning";
    } else {
        counter.className = "char-counter";
    }
}

function handlePublicInput() {
    sendTyping();
    updateCharCounter("message", "public-char-counter");
}

function handleKeyPress(event) {
    sendTyping();
    if (event.key === "Enter") {
        handleSendAction();
    }
}

// Auth Tab Switching (Sign In, Register, Quick Guest)
function switchAuthTab(tab) {
    clearAuthError();
    document.querySelectorAll(".auth-tab").forEach(t => t.classList.remove("active"));
    document.getElementById("tab-" + tab).classList.add("active");

    document.getElementById("auth-login-form").style.display = tab === "login" ? "flex" : "none";
    document.getElementById("auth-register-form").style.display = tab === "register" ? "flex" : "none";
    document.getElementById("auth-guest-form").style.display = tab === "guest" ? "flex" : "none";
}

function showAuthError(message) {
    const errorBanner = document.getElementById("username-error");
    if (errorBanner) {
        errorBanner.textContent = message;
        errorBanner.style.display = "block";
    }
}

function clearAuthError() {
    const errorBanner = document.getElementById("username-error");
    if (errorBanner) {
        errorBanner.textContent = "";
        errorBanner.style.display = "none";
    }
}

function isValidUsername(name) {
    const regex = /^[a-zA-Z0-9_-]{3,20}$/;
    return regex.test(name);
}

// Feature 42: Registration
async function handleRegister() {
    clearAuthError();
    const u = document.getElementById("reg-username").value.trim();
    const p = document.getElementById("reg-password").value.trim();
    const e = document.getElementById("reg-email").value.trim();
    const b = document.getElementById("reg-bio").value.trim();

    if (!isValidUsername(u)) {
        showAuthError("Username must be 3–20 characters (letters, numbers, _, - only).");
        return;
    }
    if (p.length < 4) {
        showAuthError("Password must be at least 4 characters.");
        return;
    }

    try {
        const resp = await fetch("/api/auth/register", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username: u, password: p, email: e, bio: b })
        });
        const data = await resp.json();
        if (resp.ok && data.success) {
            currentUserProfile = data;
            startChatSession(data.username);
        } else {
            showAuthError(data.message || (data.details ? Object.values(data.details).join(", ") : "Registration failed"));
        }
    } catch (err) {
        console.error(err);
        showAuthError("Could not reach registration server.");
    }
}

// Feature 42: Login
async function handleLogin() {
    clearAuthError();
    const u = document.getElementById("login-username").value.trim();
    const p = document.getElementById("login-password").value.trim();

    if (u === "" || p === "") {
        showAuthError("Please enter your username and password.");
        return;
    }

    try {
        const resp = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username: u, password: p })
        });
        const data = await resp.json();
        if (resp.ok && data.success) {
            currentUserProfile = data;
            startChatSession(data.username);
        } else {
            showAuthError(data.message || "Invalid username or password.");
        }
    } catch (err) {
        console.error(err);
        showAuthError("Could not reach authentication server.");
    }
}

// Guest Join
function handleGuestJoin() {
    clearAuthError();
    const u = document.getElementById("guest-username").value.trim();
    if (!isValidUsername(u)) {
        showAuthError("Username must be 3–20 characters (letters, numbers, _, - only).");
        return;
    }
    currentUserProfile = { username: u, bio: "Guest user", isGuest: true };
    startChatSession(u);
}

// Profile Modal (Feature 44)
function openProfileModal() {
    const modal = document.getElementById("profile-modal");
    if (!modal) return;

    document.getElementById("modal-username").textContent = username;
    const avatarElem = document.getElementById("modal-avatar");
    if (avatarElem) {
        avatarElem.style.backgroundColor = getAvatarColor(username);
        avatarElem.textContent = getAvatarInitials(username);
    }

    const bioInput = document.getElementById("modal-bio");
    bioInput.value = (currentUserProfile && currentUserProfile.bio) ? currentUserProfile.bio : "";

    const statusLabel = document.getElementById("modal-account-status");
    if (statusLabel) {
        statusLabel.textContent = (currentUserProfile && currentUserProfile.isGuest) ? "Guest Session" : "Registered User";
    }

    modal.style.display = "flex";
}

function closeProfileModal() {
    const modal = document.getElementById("profile-modal");
    if (modal) modal.style.display = "none";
}

async function saveProfileChanges() {
    const bio = document.getElementById("modal-bio").value.trim();
    if (currentUserProfile) {
        currentUserProfile.bio = bio;
    }

    try {
        await fetch("/api/auth/profile", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ bio: bio })
        });
    } catch (e) {
        console.error(e);
    }
    closeProfileModal();
}

// Chat History Loading (Feature 41)
async function loadRoomHistory(roomId) {
    try {
        const resp = await fetch(`/api/history/room/${encodeURIComponent(roomId)}`);
        if (resp.ok) {
            const history = await resp.json();
            history.forEach(msg => {
                messageStore.set(msg.id, msg);
            });
            reloadMessagesForCurrentView();
        }
    } catch (e) {
        console.error("Error loading room history:", e);
    }
}

async function loadPrivateHistory(targetUser) {
    try {
        const resp = await fetch(`/api/history/private?user1=${encodeURIComponent(username)}&user2=${encodeURIComponent(targetUser)}`);
        if (resp.ok) {
            const history = await resp.json();
            history.forEach(msg => {
                messageStore.set(msg.id, msg);
            });
            reloadMessagesForCurrentView();
        }
    } catch (e) {
        console.error("Error loading private history:", e);
    }
}

// Browser Notifications (Feature 32)
function updateNotificationButtonUI() {
    const btn = document.getElementById("notif-btn");
    if (!btn || !("Notification" in window)) return;

    if (Notification.permission === "granted") {
        btn.classList.add("active");
        btn.title = "Browser notifications enabled";
    } else if (Notification.permission === "denied") {
        btn.classList.remove("active");
        btn.title = "Browser notifications blocked";
    } else {
        btn.classList.remove("active");
        btn.title = "Click to enable browser notifications";
    }
}

function requestNotificationPermission() {
    if (!("Notification" in window)) {
        alert("This browser does not support desktop notifications.");
        return;
    }

    if (Notification.permission === "granted") {
        alert("Browser notifications are already enabled.");
        return;
    }

    Notification.requestPermission().then(function (permission) {
        updateNotificationButtonUI();
        if (permission === "granted") {
            showBrowserNotification("Chat Notifications", "Notifications enabled successfully!", false);
        }
    });
}

function showBrowserNotification(sender, content, isPrivate = false) {
    if (!("Notification" in window) || Notification.permission !== "granted") {
        return;
    }

    if (!document.hidden && document.hasFocus()) {
        return;
    }

    const title = isPrivate
        ? `[Private] ${sender}`
        : `${sender} (#${currentRoom})`;

    const snippet = content.length > 75 ? content.substring(0, 75) + "..." : content;

    try {
        const notif = new Notification(title, {
            body: snippet,
            icon: "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%232e3846'><path d='M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z'/></svg>"
        });

        notif.onclick = function () {
            window.focus();
            notif.close();
        };
    } catch (e) {
        console.error("Notification creation failed:", e);
    }
}

// Dark Mode Toggle (Feature 30)
function initTheme() {
    const savedTheme = localStorage.getItem("chat_theme");
    const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    const themeBtn = document.getElementById("theme-toggle-btn");

    if (savedTheme === "dark" || (!savedTheme && prefersDark)) {
        document.body.classList.add("dark-mode");
        if (themeBtn) themeBtn.textContent = "☀️";
    } else {
        document.body.classList.remove("dark-mode");
        if (themeBtn) themeBtn.textContent = "🌙";
    }
}

function toggleTheme() {
    const isDark = document.body.classList.toggle("dark-mode");
    const themeBtn = document.getElementById("theme-toggle-btn");

    if (isDark) {
        localStorage.setItem("chat_theme", "dark");
        if (themeBtn) themeBtn.textContent = "☀️";
    } else {
        localStorage.setItem("chat_theme", "light");
        if (themeBtn) themeBtn.textContent = "🌙";
    }
}

// Rooms Management (Features 33 & 34)
function renderRoomTabs() {
    const tabsContainer = document.getElementById("room-tabs");
    if (!tabsContainer) return;
    tabsContainer.innerHTML = "";

    availableRooms.forEach(function (room) {
        const pill = document.createElement("button");
        pill.className = "room-pill" + (currentRoom === room && !activePrivateRecipient ? " active" : "");
        pill.innerHTML = `<span style="opacity: 0.6; font-weight: 700;">#</span> <span>${room}</span>`;
        pill.onclick = function () {
            switchRoom(room);
        };
        tabsContainer.appendChild(pill);
    });
}

function subscribeToRoom(roomName) {
    if (!stompClient) return;
    if (currentRoomSubscription) {
        try { currentRoomSubscription.unsubscribe(); } catch (e) {}
    }
    currentRoomSubscription = stompClient.subscribe(`/topic/room/${roomName}`, function (message) {
        handleIncomingMessage(JSON.parse(message.body));
    });
}

function switchRoom(roomName) {
    if (activePrivateRecipient) {
        exitPrivateMode();
    }
    document.body.classList.add("mobile-conversation-active");
    if (currentRoom === roomName) return;

    currentRoom = roomName;
    document.getElementById("channel-icon").textContent = "#";
    document.getElementById("channel-name").textContent = currentRoom;
    const sub = document.getElementById("channel-presence-sub");
    if (sub) sub.textContent = "Public room";

    renderRoomTabs();
    reloadMessagesForCurrentView();

    subscribeToRoom(currentRoom);
    loadRoomHistory(currentRoom);
}

function toggleMobileSidebar() {
    document.body.classList.remove("mobile-conversation-active");
}

function handleSidebarSearch(query) {
    const q = (query || "").toLowerCase().trim();

    // Filter room tabs
    const roomTabs = document.querySelectorAll("#room-tabs .room-pill");
    roomTabs.forEach(function (pill) {
        const text = pill.textContent.toLowerCase();
        pill.style.display = text.includes(q) ? "flex" : "none";
    });

    // Filter contacts list
    const contacts = document.querySelectorAll("#user-list li");
    contacts.forEach(function (li) {
        const text = li.textContent.toLowerCase();
        li.style.display = text.includes(q) ? "flex" : "none";
    });
}

function openCreateRoomModal() {
    const modal = document.getElementById("create-room-modal");
    if (modal) {
        modal.style.display = "flex";
        const input = document.getElementById("new-room-name");
        input.value = "";
        input.focus();
    }
}

function closeCreateRoomModal() {
    const modal = document.getElementById("create-room-modal");
    if (modal) {
        modal.style.display = "none";
    }
}

function submitCreateRoom() {
    const input = document.getElementById("new-room-name");
    const name = input.value.trim();

    if (!name.match(/^[a-zA-Z0-9 _-]{2,30}$/)) {
        alert("Room name must be 2–30 characters (letters, numbers, spaces, _ or -).");
        return;
    }

    if (stompClient) {
        stompClient.send("/app/room/create", {}, JSON.stringify({ content: name }));
    }

    closeCreateRoomModal();
}

// Private Conversation Panel (Feature 35)
function openPrivateChat(targetUsername) {
    if (targetUsername.toLowerCase() === username.toLowerCase()) return;

    activePrivateRecipient = targetUsername;
    cancelActionContext();
    document.body.classList.add("mobile-conversation-active");

    document.getElementById("channel-icon").textContent = "🔒";
    document.getElementById("channel-name").textContent = `@${targetUsername}`;
    const sub = document.getElementById("channel-presence-sub");
    if (sub) sub.textContent = "Direct Message • Online";

    document.getElementById("exit-private-btn").style.display = "inline-block";
    document.getElementById("message").placeholder = `Message @${targetUsername}...`;

    renderRoomTabs();
    loadPrivateHistory(targetUsername);
    markPrivateMessagesAsRead(targetUsername);

    // Update contacts active state
    const contacts = document.querySelectorAll("#user-list li");
    contacts.forEach(function (li) {
        li.classList.toggle("active-chat", li.textContent.includes(targetUsername));
    });
}

function markPrivateMessagesAsRead(targetUsername) {
    if (!stompClient || !targetUsername) return;
    messageStore.forEach(function (msg) {
        if (msg.type === "PRIVATE_CHAT" &&
            msg.sender && msg.sender.toLowerCase() === targetUsername.toLowerCase() &&
            msg.status !== "READ") {
            msg.status = "READ";
            stompClient.send("/app/ack", {}, JSON.stringify({
                id: msg.id,
                sender: username,
                receiver: msg.sender,
                status: "READ"
            }));
        }
    });
}

function exitPrivateMode() {
    activePrivateRecipient = null;
    cancelActionContext();

    document.getElementById("channel-icon").textContent = "#";
    document.getElementById("channel-name").textContent = currentRoom;
    const sub = document.getElementById("channel-presence-sub");
    if (sub) sub.textContent = "Public room";

    document.getElementById("exit-private-btn").style.display = "none";
    document.getElementById("message").placeholder = "Type a message...";

    renderRoomTabs();
    reloadMessagesForCurrentView();

    // Clear contact active highlight
    const contacts = document.querySelectorAll("#user-list li");
    contacts.forEach(function (li) {
        li.classList.remove("active-chat");
    });
}

// Action Context: Reply (Feature 36) & Edit (Feature 38)
function startReply(messageId) {
    const msg = messageStore.get(messageId);
    if (!msg || msg.deleted) return;

    actionContext = {
        type: "reply",
        messageId: messageId,
        sender: msg.sender,
        content: msg.content
    };

    const banner = document.getElementById("action-context-banner");
    banner.className = "action-context-banner";
    banner.style.display = "flex";

    document.getElementById("context-title").textContent = `Replying to ${msg.sender}`;
    document.getElementById("context-snippet").textContent = `"${msg.content}"`;

    const input = document.getElementById("message");
    input.focus();
}

function startEdit(messageId) {
    const msg = messageStore.get(messageId);
    if (!msg || msg.deleted || msg.sender.toLowerCase() !== username.toLowerCase()) return;

    actionContext = {
        type: "edit",
        messageId: messageId,
        sender: msg.sender,
        content: msg.content
    };

    const banner = document.getElementById("action-context-banner");
    banner.className = "action-context-banner edit-mode";
    banner.style.display = "flex";

    document.getElementById("context-title").textContent = "Editing message";
    document.getElementById("context-snippet").textContent = `"${msg.content}"`;

    const input = document.getElementById("message");
    input.value = msg.content;
    input.focus();
    updateCharCounter("message", "public-char-counter");

    document.getElementById("send-btn").textContent = "Save";
}

function cancelActionContext() {
    if (actionContext && actionContext.type === "edit") {
        document.getElementById("message").value = "";
        updateCharCounter("message", "public-char-counter");
    }

    actionContext = null;
    const banner = document.getElementById("action-context-banner");
    if (banner) banner.style.display = "none";
    document.getElementById("send-btn").textContent = "Send";
}

// Message Reactions (Feature 37)
function toggleReaction(messageId, emoji) {
    if (!stompClient || !username) return;

    const req = {
        id: messageId,
        emoji: emoji,
        sender: username,
        roomId: currentRoom,
        receiver: activePrivateRecipient
    };

    stompClient.send("/app/react", {}, JSON.stringify(req));
}

// Message Delete (Feature 39)
function deleteMessage(messageId) {
    const msg = messageStore.get(messageId);
    if (!msg || msg.sender.toLowerCase() !== username.toLowerCase()) return;

    if (!confirm("Are you sure you want to delete this message?")) return;

    if (stompClient) {
        stompClient.send("/app/delete", {}, JSON.stringify({
            id: messageId,
            sender: username
        }));
    }
}

// Connect to Chat & Connection Recovery (Feature 49)
function startChatSession(userName) {
    username = userName;
    connect();
}

function connect() {
    clearAuthError();

    const socket = new SockJS("/ws");
    stompClient = StompJs.Stomp.over(socket);
    stompClient.debug = function () {};

    stompClient.connect({}, function () {
        // Connection success: clear reconnect banner
        hideReconnectBanner();
        reconnectAttempts = 0;
        isReconnecting = false;

        if ("Notification" in window && Notification.permission === "default") {
            Notification.requestPermission().then(updateNotificationButtonUI);
        }

        // Subscribe to rooms updates
        stompClient.subscribe("/topic/rooms", function (message) {
            const data = JSON.parse(message.body);
            if (data.type === "ROOM_LIST" && data.content) {
                availableRooms = data.content.split(", ").map(r => r.trim()).filter(r => r.length > 0);
                renderRoomTabs();
            }
        });

        // Request current rooms
        stompClient.send("/app/rooms", {}, JSON.stringify({}));

        // Subscribe to public presence topics (JOIN, LEAVE, USERS)
        stompClient.subscribe("/topic/public", function (message) {
            const chatMessage = JSON.parse(message.body);
            if (chatMessage.type === "USERS" || chatMessage.type === "JOIN" || chatMessage.type === "LEAVE") {
                handleIncomingMessage(chatMessage);
            }
        });

        // Subscribe to current room
        subscribeToRoom(currentRoom);

        // Feature 46 & 47: Subscribe to authorized private destination for this user
        stompClient.subscribe("/topic/private/" + username, function (message) {
            handleIncomingPrivateMessage(JSON.parse(message.body));
        });

        // Send JOIN message
        stompClient.send(
            "/app/join",
            {},
            JSON.stringify({
                sender: username,
                content: username + " joined the chat",
                type: "JOIN"
            })
        );

        // Feature 41: Load room chat history from database
        loadRoomHistory(currentRoom);

    }, function (error) {
        console.error("WebSocket connection error:", error);
        triggerReconnect();
    });
}

// Feature 49: Reconnection with Exponential Backoff
function showReconnectBanner() {
    const banner = document.getElementById("reconnect-banner");
    if (banner) banner.style.display = "block";
}

function hideReconnectBanner() {
    const banner = document.getElementById("reconnect-banner");
    if (banner) banner.style.display = "none";
}

function triggerReconnect() {
    if (!username || !hasJoined) {
        showAuthError("Could not connect to the chat server.");
        return;
    }

    showReconnectBanner();
    isReconnecting = true;

    reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(1.5, reconnectAttempts), 10000);

    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function () {
        console.log(`Attempting reconnect #${reconnectAttempts}...`);
        connect();
    }, delay);
}

function handleIncomingMessage(chatMessage) {
    if (chatMessage.type === "USERS") {
        updateOnlineUsers(chatMessage.content);
    } else if (chatMessage.type === "USERNAME_TAKEN") {
        if (chatMessage.sender === username) {
            showAuthError(chatMessage.content);
            try { stompClient.disconnect(); } catch (e) {}
            stompClient = null;
            username = "";
            hasJoined = false;
        }
    } else if (chatMessage.type === "TYPING") {
        if (chatMessage.sender !== username) {
            document.getElementById("typing-indicator").innerHTML = `
                <span>${chatMessage.sender} is typing</span>
                <span class="typing-dots"><span>.</span><span>.</span><span>.</span></span>
            `;
        }
    } else if (chatMessage.type === "JOIN") {
        if (chatMessage.sender === username) {
            hasJoined = true;
            document.getElementById("username-section").style.display = "none";
            document.getElementById("chat-section").style.display = "block";

            document.getElementById("my-username-display").textContent = username;
            const myAvatar = document.getElementById("my-avatar");
            if (myAvatar) {
                myAvatar.style.backgroundColor = getAvatarColor(username);
                myAvatar.textContent = getAvatarInitials(username);
            }
            renderRoomTabs();
        }

        displayMessage(chatMessage);
    } else if (chatMessage.type === "LEAVE") {
        displayMessage(chatMessage);
    } else if (chatMessage.type === "STATUS_UPDATE") {
        updateMessageDeliveryStatus(chatMessage.id, chatMessage.status);
    } else if (chatMessage.type === "CHAT") {
        document.getElementById("typing-indicator").textContent = "";

        if (chatMessage.id) {
            messageStore.set(chatMessage.id, chatMessage);
        }

        // Own message echoing back from server
        if (chatMessage.sender.toLowerCase() === username.toLowerCase()) {
            if (chatMessage.id) {
                renderedMessageIds.add(chatMessage.id);
            }
            // Message safely received and saved by server.
            // Keep status as SENT (single grey tick). Only another recipient can acknowledge delivery.
            return;
        }

        // Another user in the room sent this message
        displayMessage(chatMessage);
        if (chatMessage.sender.toLowerCase() !== username.toLowerCase()) {
            showBrowserNotification(chatMessage.sender, chatMessage.content, false);
        }

        // Send delivery acknowledgment back to the room for this message
        if (stompClient && chatMessage.id) {
            stompClient.send("/app/ack", {}, JSON.stringify({
                id: chatMessage.id,
                sender: username,
                roomId: currentRoom,
                status: "DELIVERED"
            }));
        }
    } else if (chatMessage.type === "REACTION") {
        updateMessageReactionsInUI(chatMessage);
    } else if (chatMessage.type === "EDIT") {
        updateMessageEditInUI(chatMessage);
    } else if (chatMessage.type === "DELETE") {
        updateMessageDeleteInUI(chatMessage);
    }
}

function handleIncomingPrivateMessage(chatMessage) {
    if (chatMessage.sender === "SYSTEM") {
        alert(chatMessage.content);
        if (chatMessage.id) {
            updateMessageDeliveryStatus(chatMessage.id, "FAILED");
        }
        return;
    }

    if (chatMessage.type === "STATUS_UPDATE") {
        updateMessageDeliveryStatus(chatMessage.id, chatMessage.status);
        return;
    }

    if (chatMessage.type === "REACTION") {
        updateMessageReactionsInUI(chatMessage);
        return;
    }

    if (chatMessage.type === "EDIT") {
        updateMessageEditInUI(chatMessage);
        return;
    }

    if (chatMessage.type === "DELETE") {
        updateMessageDeleteInUI(chatMessage);
        return;
    }

    if (chatMessage.id) {
        messageStore.set(chatMessage.id, chatMessage);
    }

    if (chatMessage.sender.toLowerCase() === username.toLowerCase()) {
        // Own private message echo: preserve SENT status
        if (chatMessage.id) {
            renderedMessageIds.add(chatMessage.id);
        }
        return;
    }

    // Message sent to ME from someone else!
    displayMessage(chatMessage);
    if (chatMessage.sender.toLowerCase() !== username.toLowerCase()) {
        showBrowserNotification(chatMessage.sender, chatMessage.content, true);
    }

    // Determine if recipient is currently reading this private thread
    const isCurrentlyReading = (activePrivateRecipient &&
        activePrivateRecipient.toLowerCase() === chatMessage.sender.toLowerCase() &&
        !document.hidden);

    const ackStatus = isCurrentlyReading ? "READ" : "DELIVERED";

    if (stompClient && chatMessage.id) {
        stompClient.send("/app/ack", {}, JSON.stringify({
            id: chatMessage.id,
            sender: username,
            receiver: chatMessage.sender,
            status: ackStatus
        }));
    }
}

// Unified Send / Edit Action
function handleSendAction() {
    const messageInput = document.getElementById("message");
    const content = messageInput.value.trim();

    if (content === "") return;

    if (content.length > 500) {
        alert("Message cannot exceed 500 characters.");
        return;
    }

    // Edit Mode (Feature 38)
    if (actionContext && actionContext.type === "edit") {
        if (stompClient) {
            stompClient.send("/app/edit", {}, JSON.stringify({
                id: actionContext.messageId,
                sender: username,
                content: content
            }));
        }
        cancelActionContext();
        messageInput.value = "";
        updateCharCounter("message", "public-char-counter");
        return;
    }

    document.getElementById("typing-indicator").textContent = "";

    const messageId = "msg-" + Date.now() + "-" + Math.random().toString(36).substring(2, 8);
    const currentTime = getLocalTimeString();
    const currentDate = getLocalDateString();

    const chatMessage = {
        id: messageId,
        sender: username,
        content: content,
        time: currentTime,
        date: currentDate,
        status: "SENT",
        reactions: {}
    };

    // Attach reply info if in Reply Mode (Feature 36)
    if (actionContext && actionContext.type === "reply") {
        chatMessage.replyToId = actionContext.messageId;
        chatMessage.replyToSender = actionContext.sender;
        chatMessage.replyToContent = actionContext.content;
    }

    if (activePrivateRecipient) {
        chatMessage.type = "PRIVATE_CHAT";
        chatMessage.receiver = activePrivateRecipient;

        displayMessage(chatMessage);

        stompClient.send(
            "/app/private",
            {},
            JSON.stringify(chatMessage)
        );
    } else {
        chatMessage.type = "CHAT";
        chatMessage.roomId = currentRoom;

        displayMessage(chatMessage);

        stompClient.send(
            "/app/chat",
            {},
            JSON.stringify(chatMessage)
        );
    }

    messageStore.set(messageId, chatMessage);

    cancelActionContext();
    messageInput.value = "";
    updateCharCounter("message", "public-char-counter");
}

function displayMessage(msg) {
    // Prevent duplicate DOM rendering
    if (msg.id && renderedMessageIds.has(msg.id)) {
        if (msg.status) {
            updateMessageDeliveryStatus(msg.id, msg.status);
        }
        return;
    }

    const isPrivate = msg.type === "PRIVATE_CHAT";

    // Filtering: In private mode, only display messages with active recipient
    if (activePrivateRecipient) {
        if (!isPrivate) return;
        const otherParty = msg.sender.toLowerCase() === username.toLowerCase() ? msg.receiver : msg.sender;
        if (otherParty.toLowerCase() !== activePrivateRecipient.toLowerCase()) return;
    } else {
        // In room mode: only display public messages for this room (or SYSTEM messages)
        if (isPrivate) return;
        if (msg.roomId && msg.roomId !== currentRoom && msg.type !== "JOIN" && msg.type !== "LEAVE") {
            return;
        }
    }

    const messages = document.getElementById("messages");
    ensureDateDivider(msg.date);

    // System message priority
    const isSystem = (msg.sender === "SYSTEM" || msg.type === "JOIN" || msg.type === "LEAVE");
    const isMine = (!isSystem && msg.sender.toLowerCase() === username.toLowerCase());

    const rowElement = document.createElement("div");
    rowElement.className = "message-row";
    if (msg.id) rowElement.id = `row-${msg.id}`;

    if (isSystem) {
        rowElement.classList.add("system-row");
    } else if (isMine) {
        rowElement.classList.add("my-row");
    } else {
        rowElement.classList.add("other-row");
    }

    let avatarElement = null;
    if (!isSystem) {
        avatarElement = createAvatarElement(msg.sender, "avatar-sm");
    }

    const wrapper = document.createElement("div");
    wrapper.className = "message-wrapper";

    const bubble = document.createElement("div");
    bubble.className = "message";
    if (msg.id) bubble.id = msg.id;

    if (isPrivate) bubble.classList.add("private-message");
    if (isSystem) {
        bubble.classList.add("system-message");
    } else if (isMine) {
        bubble.classList.add("my-message");
    } else {
        bubble.classList.add("other-message");
    }

    // Action toolbar on hover
    if (!isSystem && !msg.deleted) {
        const actionBar = document.createElement("div");
        actionBar.className = "message-action-bar";

        // Reactions
        const emojis = ["👍", "❤️", "😂"];
        emojis.forEach(function (emoji) {
            const btn = document.createElement("button");
            btn.className = "action-btn";
            btn.textContent = emoji;
            btn.title = `React with ${emoji}`;
            btn.onclick = function (e) {
                e.stopPropagation();
                toggleReaction(msg.id, emoji);
            };
            actionBar.appendChild(btn);
        });

        // Reply
        const replyBtn = document.createElement("button");
        replyBtn.className = "action-btn";
        replyBtn.textContent = "↩️";
        replyBtn.title = "Reply";
        replyBtn.onclick = function (e) {
            e.stopPropagation();
            startReply(msg.id);
        };
        actionBar.appendChild(replyBtn);

        // Edit and Delete if my message
        if (isMine) {
            const editBtn = document.createElement("button");
            editBtn.className = "action-btn";
            editBtn.textContent = "✏️";
            editBtn.title = "Edit message";
            editBtn.onclick = function (e) {
                e.stopPropagation();
                startEdit(msg.id);
            };
            actionBar.appendChild(editBtn);

            const deleteBtn = document.createElement("button");
            deleteBtn.className = "action-btn";
            deleteBtn.textContent = "🗑️";
            deleteBtn.title = "Delete message";
            deleteBtn.onclick = function (e) {
                e.stopPropagation();
                deleteMessage(msg.id);
            };
            actionBar.appendChild(deleteBtn);
        }

        bubble.appendChild(actionBar);
    }

    // Reply Quote (Feature 36)
    if (msg.replyToSender && !msg.deleted) {
        const quoteBox = document.createElement("div");
        quoteBox.className = "message-reply-quote";
        quoteBox.innerHTML = `
            <span class="quote-sender">Replying to ${msg.replyToSender}</span>
            <span class="quote-text">${msg.replyToContent || "Message"}</span>
        `;
        quoteBox.onclick = function () {
            const originalElem = document.getElementById(msg.replyToId);
            if (originalElem) {
                originalElem.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        };
        bubble.appendChild(quoteBox);
    }

    // Message Content
    const contentRow = document.createElement("div");
    contentRow.className = "message-content-row";
    if (msg.deleted) {
        contentRow.innerHTML = `<span class="deleted-content">🚫 This message was deleted</span>`;
    } else if (isSystem) {
        contentRow.innerHTML = msg.content;
    } else {
        const prefix = isPrivate ? `<strong>[Private] ${msg.sender}:</strong> ` : `<strong>${msg.sender}:</strong> `;
        contentRow.innerHTML = `${prefix}<span class="text-content">${msg.content}</span>`;
    }
    bubble.appendChild(contentRow);

    // Metadata: time + edited badge + delivery ticks
    const metaRow = document.createElement("div");
    metaRow.className = "message-meta";

    let editedTag = msg.edited && !msg.deleted ? `<span class="edited-badge">(edited)</span>` : "";

    let statusHtml = "";
    if (isMine && !isSystem && msg.status) {
        const st = (msg.status || "SENT").toUpperCase();
        let symbol = "✓";
        let title = "Sent to server";
        if (st === "READ") {
            symbol = "✓✓";
            title = "Read / Acknowledged";
        } else if (st === "DELIVERED") {
            symbol = "✓✓";
            title = "Delivered";
        } else if (st === "FAILED") {
            symbol = "⚠️";
            title = "Failed to deliver";
        }
        statusHtml = `<span class="delivery-status ${st.toLowerCase()}" title="${title}">${symbol}</span>`;
    }

    metaRow.innerHTML = `
        <span class="message-time">${msg.time || getLocalTimeString()}</span>
        ${editedTag}
        ${statusHtml}
    `;
    bubble.appendChild(metaRow);

    wrapper.appendChild(bubble);

    // Reactions Pill Row (Feature 37)
    if (msg.reactions && Object.keys(msg.reactions).length > 0 && !msg.deleted) {
        const reactionsRow = renderReactionsPills(msg.id, msg.reactions);
        wrapper.appendChild(reactionsRow);
    }

    if (avatarElement) {
        rowElement.appendChild(avatarElement);
    }
    rowElement.appendChild(wrapper);

    messages.appendChild(rowElement);
    messages.scrollTop = messages.scrollHeight;

    if (msg.id) {
        renderedMessageIds.add(msg.id);
    }
}

function renderReactionsPills(messageId, reactionsMap) {
    const row = document.createElement("div");
    row.className = "message-reactions-row";
    row.id = `reactions-${messageId}`;

    Object.keys(reactionsMap).forEach(function (emoji) {
        const users = reactionsMap[emoji];
        if (users && users.length > 0) {
            const hasMyReaction = users.some(u => u.toLowerCase() === username.toLowerCase());
            const pill = document.createElement("button");
            pill.className = "reaction-pill" + (hasMyReaction ? " active" : "");
            pill.title = `Reacted by: ${users.join(", ")}`;
            pill.innerHTML = `${emoji} <span class="count">${users.length}</span>`;
            pill.onclick = function () {
                toggleReaction(messageId, emoji);
            };
            row.appendChild(pill);
        }
    });

    return row;
}

function updateMessageReactionsInUI(reactionMsg) {
    const msg = messageStore.get(reactionMsg.id);
    if (msg) {
        msg.reactions = reactionMsg.reactions;
    }

    const bubble = document.getElementById(reactionMsg.id);
    if (!bubble) return;

    const wrapper = bubble.parentElement;
    if (!wrapper) return;

    const existingRow = document.getElementById(`reactions-${reactionMsg.id}`);
    if (existingRow) {
        existingRow.remove();
    }

    if (reactionMsg.reactions && Object.keys(reactionMsg.reactions).length > 0) {
        const newRow = renderReactionsPills(reactionMsg.id, reactionMsg.reactions);
        wrapper.appendChild(newRow);
    }
}

function updateMessageEditInUI(editMsg) {
    const msg = messageStore.get(editMsg.id);
    if (msg) {
        msg.content = editMsg.content;
        msg.edited = true;
    }

    const bubble = document.getElementById(editMsg.id);
    if (!bubble) return;

    const textSpan = bubble.querySelector(".text-content");
    if (textSpan) {
        textSpan.textContent = editMsg.content;
    }

    const metaRow = bubble.querySelector(".message-meta");
    if (metaRow && !metaRow.querySelector(".edited-badge")) {
        const editedBadge = document.createElement("span");
        editedBadge.className = "edited-badge";
        editedBadge.textContent = "(edited)";
        metaRow.insertBefore(editedBadge, metaRow.firstChild.nextSibling);
    }
}

function updateMessageDeleteInUI(deleteMsg) {
    const msg = messageStore.get(deleteMsg.id);
    if (msg) {
        msg.deleted = true;
        msg.content = "This message was deleted";
    }

    const bubble = document.getElementById(deleteMsg.id);
    if (!bubble) return;

    const actionBar = bubble.querySelector(".message-action-bar");
    if (actionBar) actionBar.remove();

    const quote = bubble.querySelector(".message-reply-quote");
    if (quote) quote.remove();

    const contentRow = bubble.querySelector(".message-content-row");
    if (contentRow) {
        contentRow.innerHTML = `<span class="deleted-content">🚫 This message was deleted</span>`;
    }

    const reactionsRow = document.getElementById(`reactions-${deleteMsg.id}`);
    if (reactionsRow) reactionsRow.remove();
}

function updateMessageDeliveryStatus(messageId, status) {
    if (!messageId || !status) return;

    const msg = messageStore.get(messageId);
    if (msg) {
        // Prevent state downgrade: once READ, never revert to DELIVERED or SENT
        if (msg.status === "READ" && status !== "READ") {
            return;
        }
        msg.status = status;
    }

    const msgElem = document.getElementById(messageId);
    if (!msgElem) return;

    const statusElem = msgElem.querySelector(".delivery-status");
    if (!statusElem) return;

    const st = status.toUpperCase();
    statusElem.className = `delivery-status ${st.toLowerCase()}`;
    if (st === "READ") {
        statusElem.textContent = "✓✓";
        statusElem.title = "Read / Acknowledged";
    } else if (st === "DELIVERED") {
        statusElem.textContent = "✓✓";
        statusElem.title = "Delivered";
    } else if (st === "SENT") {
        statusElem.textContent = "✓";
        statusElem.title = "Sent to server";
    } else if (st === "FAILED") {
        statusElem.textContent = "⚠️";
        statusElem.title = "Failed to deliver";
    }
}

function reloadMessagesForCurrentView() {
    const messages = document.getElementById("messages");
    messages.innerHTML = "";
    lastRenderedDate = null;
    renderedMessageIds.clear();

    messageStore.forEach(function (msg) {
        displayMessage(msg);
    });
}

function updateOnlineUsers(userString) {
    const userList = document.getElementById("user-list");
    const userTitle = document.getElementById("online-users-title");

    userList.innerHTML = "";

    const users = userString ? userString.split(", ") : [];
    const validUsers = users.filter(u => u.trim() !== "");
    userTitle.textContent = `ONLINE CONTACTS (${validUsers.length})`;

    validUsers.forEach(function (user) {
        const listItem = document.createElement("li");
        if (activePrivateRecipient && activePrivateRecipient.toLowerCase() === user.toLowerCase()) {
            listItem.classList.add("active-chat");
        }

        const avatar = createAvatarElement(user, "avatar-sm");

        const infoDiv = document.createElement("div");
        infoDiv.style.display = "flex";
        infoDiv.style.flexDirection = "column";
        infoDiv.style.flex = "1";
        infoDiv.style.overflow = "hidden";

        const nameSpan = document.createElement("span");
        nameSpan.style.fontWeight = "600";
        nameSpan.style.fontSize = "13.5px";
        nameSpan.textContent = user + (user.toLowerCase() === username.toLowerCase() ? " (You)" : "");

        const statusSub = document.createElement("span");
        statusSub.style.fontSize = "11.5px";
        statusSub.style.color = "var(--text-secondary)";
        statusSub.textContent = user.toLowerCase() === username.toLowerCase() ? "Active now" : "Click to chat";

        infoDiv.appendChild(nameSpan);
        infoDiv.appendChild(statusSub);

        const dot = document.createElement("span");
        dot.className = "online-dot";
        dot.title = "Online";

        listItem.appendChild(avatar);
        listItem.appendChild(infoDiv);
        listItem.appendChild(dot);

        if (user.toLowerCase() !== username.toLowerCase()) {
            listItem.title = `Click to message @${user}`;
            listItem.onclick = function () {
                openPrivateChat(user);
            };
        }

        userList.appendChild(listItem);
    });
}

function sendTyping() {
    if (stompClient && username !== "") {
        stompClient.send(
            "/app/typing",
            {},
            JSON.stringify({
                sender: username,
                type: "TYPING"
            })
        );
    }
}

function leaveChat() {
    if (stompClient && hasJoined) {
        try {
            stompClient.send(
                "/app/leave",
                {},
                JSON.stringify({
                    sender: username,
                    type: "LEAVE"
                })
            );
        } catch (e) {
            console.error("Error sending leave frame:", e);
        }

        try {
            stompClient.disconnect(function () {
                console.log("Stomp disconnected cleanly.");
            });
        } catch (e) {
            console.error("Error disconnecting stomp client:", e);
        }
    }

    try {
        fetch("/api/auth/logout", { method: "POST" });
    } catch (e) {}

    stompClient = null;
    hasJoined = false;
    username = "";
    currentUserProfile = null;
    lastRenderedDate = null;
    currentRoom = "General";
    activePrivateRecipient = null;
    actionContext = null;
    messageStore.clear();
    renderedMessageIds.clear();
    currentRoomSubscription = null;

    if (reconnectTimer) clearTimeout(reconnectTimer);
    hideReconnectBanner();

    document.getElementById("chat-section").style.display = "none";
    document.getElementById("username-section").style.display = "block";
    clearAuthError();

    document.getElementById("messages").innerHTML = "";
    document.getElementById("user-list").innerHTML = "";
    document.getElementById("online-users-title").textContent = "Online Users (0)";
    document.getElementById("message").value = "";
    document.getElementById("public-char-counter").textContent = "0 / 500";
    document.getElementById("typing-indicator").textContent = "";

    const banner = document.getElementById("action-context-banner");
    if (banner) banner.style.display = "none";
}

document.addEventListener("DOMContentLoaded", function () {
    initTheme();
    updateNotificationButtonUI();
});

window.addEventListener("focus", function () {
    if (activePrivateRecipient) {
        markPrivateMessagesAsRead(activePrivateRecipient);
    }
});