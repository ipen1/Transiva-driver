package com.transiva.app;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class PlayStorePolicyInvariantTest {

    private File repositoryRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        if ("app".equals(dir.getName()) && dir.getParentFile() != null) {
            return dir.getParentFile();
        }
        return dir;
    }

    private String readFromRepo(String relativePath) throws Exception {
        Path path = new File(repositoryRoot(), relativePath).toPath();
        assertTrue("Required source file missing: " + path, Files.exists(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void manifestAllowsFullScreenOnlyForCallingAndAvoidsBroadMediaPermissions() throws Exception {
        String manifest = readFromRepo("app/src/main/AndroidManifest.xml");

        // Incoming WebRTC calls are the sole legitimate full-screen use case.
        assertTrue(manifest.contains("android.permission.USE_FULL_SCREEN_INTENT"));
        assertTrue(manifest.contains("android:name=\".WebRtcCallActivity\""));
        assertTrue(manifest.contains("android:showWhenLocked=\"true\""));
        assertTrue(manifest.contains("android:turnScreenOn=\"true\""));

        String[] forbiddenMedia = {
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        for (String permission : forbiddenMedia) {
            assertFalse("Broad media/storage permission present: " + permission,
                    manifest.contains(permission));
        }
    }

    @Test
    public void requiredDriverLocationPermissionsRemainDeclared() throws Exception {
        String manifest = readFromRepo("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"));
        assertTrue(manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION"));
    }

    @Test
    public void firebaseServiceUsesGuardedFullScreenIntentOnlyForIncomingWebRtcCalls() throws Exception {
        String source = readFromRepo(
                "app/src/main/java/com/transiva/app/TransivaFirebaseService.java");

        assertTrue(source.contains("transiva_call_channel_v5"));
        assertTrue(source.contains("incomingCallNotification"));
        assertTrue(source.contains("\"webrtc_call\".equals(type)"));
        assertTrue(source.contains("\"incoming_call\".equalsIgnoreCase"));
        assertTrue(source.contains("canUseFullScreenCallIntent()"));
        assertTrue(source.contains("manager.canUseFullScreenIntent()"));
        assertTrue(source.contains("builder.setFullScreenIntent(pendingIntent, true)"));
        assertTrue(source.contains("IncomingCallAlertManager.start(this, callNotificationId)"));
        assertTrue(source.contains("IncomingCallActionReceiver.ACTION_REJECT"));
        assertTrue(source.contains("acceptIntent.putExtra(\"auto_accept\", true)"));

        // Keep the full-screen call surface narrow: exactly one builder call site.
        assertEquals(1, occurrences(source, "setFullScreenIntent("));
    }

    @Test
    public void mediaSelectionUsesSystemDocumentPicker() throws Exception {
        String chat = readFromRepo(
                "app/src/main/java/com/transiva/app/DriverChatRoomActivity.java");
        String topUp = readFromRepo(
                "app/src/main/java/com/transiva/app/DriverTopUpActivity.java");
        assertTrue(chat.contains("ACTION_OPEN_DOCUMENT"));
        assertTrue(topUp.contains("ACTION_OPEN_DOCUMENT"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
