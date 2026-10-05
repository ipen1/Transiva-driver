# Transiva Driver — Play submission checklist

## Binary
- [ ] `testDebugUnitTest` passes
- [ ] `lintRelease` passes
- [ ] signed `bundleRelease` passes
- [ ] `tools/playstore_release_gate.py` passes
- [ ] merged manifest contains one location FGS and one call FGS with `microphone|camera` and matching permissions
- [ ] no `FOREGROUND_SERVICE_DATA_SYNC` permission in release manifest

## App content
- [ ] Privacy Policy URL entered: https://transiva.my.id/server/privacy.html
- [ ] Privacy page loads publicly without login
- [ ] Background Location declaration completed
- [ ] <=30s real-device location video uploaded
- [ ] Foreground Service `location`, `microphone`, and `camera` declarations completed
- [ ] FGS functionality/user-impact/video fields completed
- [ ] Full-Screen Intent declaration completed accurately for incoming WebRTC calls
- [ ] Data Safety completed using `DATA_SAFETY_FORM.md`
- [ ] App access reviewer credentials supplied

## Reviewer test conditions
- [ ] reviewer driver account remains active
- [ ] clean install can sign in
- [ ] OFFLINE -> ONLINE triggers prominent disclosure before permission flow
- [ ] location service does not run while OFFLINE with no active trip
- [ ] incoming call falls back to call notification if full-screen special access is unavailable
- [ ] accepted voice/video call survives Home/Back; PiP is tested where supported
- [ ] microphone/camera permissions and ending from the call notification are tested
- [ ] core app works without overlay/bubble permission
