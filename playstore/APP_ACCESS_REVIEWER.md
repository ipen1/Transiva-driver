# Play Console — App access instructions for reviewers

Transiva Driver requires authentication, so Play review must receive credentials that remain valid for the entire review period.

## Create a dedicated reviewer account
Do not put real credentials in source control. Create a server-side account such as:

- Role: Driver
- Account status: Active/approved
- Device binding: disabled for the review account, or preconfigured so a clean Play install can sign in
- OTP/SMS requirement: disabled for review, unless Play reviewers can complete it using the instructions you provide
- PIN requirement: if enabled, provide the PIN in Play Console App access instructions
- Balance/order state: enough to navigate all reviewable screens without blocking dialogs

## Paste-ready Play Console instructions
Replace the bracketed placeholders before submission.

**Username / phone:** `[PLAY_REVIEW_DRIVER_USERNAME]`  
**Password:** `[PLAY_REVIEW_DRIVER_PASSWORD]`  
**PIN, if requested:** `[PLAY_REVIEW_PIN]`

Steps:
1. Launch Transiva Driver.
2. Enter the reviewer username and password above and tap Login.
3. If a PIN screen appears, enter the reviewer PIN above.
4. The reviewer account opens the Driver dashboard in OFFLINE state.
5. To review background location, tap the ONLINE status control. The app will first show its prominent location disclosure, then Android's location permission flow. Background location is used only while ONLINE or during an active trip.
6. To review incoming-call behavior, open Driver Settings > incoming call/full-screen notification setting if Android 14+ requires user-granted special access. Use the supplied Transiva customer test account/process to place a WebRTC call to this review driver account.
7. The app does not require overlay/bubble permission for core order, trip, chat, navigation, or calling functionality. Bubble/overlay is optional.

## If a second Customer account is needed to trigger a call/order
Add these details only in Play Console, never in the repository:

**Customer username:** `[PLAY_REVIEW_CUSTOMER_USERNAME]`  
**Customer password:** `[PLAY_REVIEW_CUSTOMER_PASSWORD]`

Explain the shortest path for the reviewer to place a test call/order. Keep both accounts active until review is complete.

## Reviewer reliability rules
- Do not expire the password during review.
- Do not bind the account permanently to your own test device.
- Do not require contact with support before login.
- Do not geo-block the reviewer unless instructions provide a supported test location.
- Do not require a live driver/admin manual approval after the reviewer starts testing.
