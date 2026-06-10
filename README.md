# 🃏 UNO Online — Android App (Kotlin)

Multiplayer UNO card game for Android. Supports up to **6 players**, real-time gameplay via Socket.IO, voice chat via WebRTC, and beautiful custom-drawn colorful cards.

> 🖥️ Backend repo: [akshayrai005/UNO-Backend](https://github.com/akshayrai005/UNO-Backend)
> 🌐 Live Server: `https://uno-v6tv.onrender.com`

---

## 📁 Project Structure

```
UNO-Android/
├── app/
│   └── src/main/
│       ├── java/com/uno/game/
│       │   ├── models/         # UnoCard, Player, Room, GameState
│       │   ├── network/        # SocketManager (Socket.IO) + ApiService (OkHttp)
│       │   ├── audio/          # VoiceChatManager (WebRTC) + SoundManager
│       │   ├── ui/
│       │   │   ├── home/       # HomeActivity — username + create/join room
│       │   │   ├── lobby/      # LobbyActivity — waiting room
│       │   │   └── game/       # GameActivity + CardHandAdapter + UnoCardView
│       │   └── utils/          # PreferencesManager
│       └── res/
│           ├── layout/         # All XML layouts
│           ├── drawable/       # Circle shape drawable
│           ├── mipmap-*/       # Launcher icons
│           ├── raw/            # Sound effects (MP3)
│           └── values/         # themes.xml, strings.xml
└── build.gradle
```

---

## 🚀 Build APK in Android Studio

### 1. Clone this repo
```bash
git clone https://github.com/akshayrai005/UNO-Android.git
```

### 2. Open in Android Studio
`File → Open → select the UNO-Android folder`

### 3. Server URL is already set
The live backend is pre-configured in `app/build.gradle`:
```groovy
buildConfigField "String", "SERVER_URL", '"https://uno-v6tv.onrender.com"'
```

### 4. Generate APK
```
Build → Generate Signed Bundle / APK → APK
→ Create keystore → select release → Finish
```
APK output: `app/release/app-release.apk`

---

## 🎮 Features
- ✅ Up to 6 players online
- ✅ 7 cards dealt to each player
- ✅ Custom Canvas-drawn colorful UNO cards
- ✅ Skip / Reverse / Draw 2 / Wild / Wild +4
- ✅ Card stacking support
- ✅ Auto UNO alert when 1 card remains 🔊
- ✅ UNO button + challenge system
- ✅ Voice chat (WebRTC peer-to-peer)
- ✅ Sound effects
- ✅ Avatar color picker

---

## 📦 Dependencies
- Socket.IO client — real-time game events
- OkHttp — REST API calls
- WebRTC (stream-webrtc-android) — voice chat
- Lottie — animations
- Material Components — UI
