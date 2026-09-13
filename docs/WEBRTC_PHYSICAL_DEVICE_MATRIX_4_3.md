# WebRTC Physical Device Matrix — Stability 4.3

This checklist must be executed on real hardware before final production sign-off. CI emulators cover Android API 26/29/31/34/35, but they do not replace OEM audio, lock-screen, Doze and push-delivery behavior.

| Android | Foreground | Background | Recent cleared | Locked screen | Duplicate FCM | Process recreation | Result |
|---|---|---|---|---|---|---|---|
| 8 / API 26 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | PENDING DEVICE |
| 10 / API 29 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | PENDING DEVICE |
| 12 / API 31 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | PENDING DEVICE |
| 14 / API 34 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | PENDING DEVICE |
| 15 / API 35 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | PENDING DEVICE |

For every cell verify: one ring only, one call UI only, correct caller identity, Accept/Reject once, two-way audio after accept, notification removed after terminal state, and no relaunch after ended/rejected/missed push.
