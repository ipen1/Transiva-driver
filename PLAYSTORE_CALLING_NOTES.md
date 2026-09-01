# Transiva Driver — Incoming call / Play Store notes

- `USE_FULL_SCREEN_INTENT` is used only for `webrtc_call` + `incoming_call`.
- Android 14+ checks `NotificationManager.canUseFullScreenIntent()` before attaching a full-screen intent.
- If special access is unavailable, the app falls back to the high-importance `CATEGORY_CALL` heads-up notification.
- Driver Settings exposes **Layar Panggilan Masuk** and opens `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` only after an explanatory dialog.
- Orders, chat, promos, wallet updates, broadcasts, and overlays never use full-screen intent.
- Play Console: declare the Full-screen intent permission accurately. Because Transiva Driver's overall app purpose is broader than calling, do not claim that calling is the sole/core app purpose if that is not true; expect Google Play to require user-granted special access instead of default grant.
