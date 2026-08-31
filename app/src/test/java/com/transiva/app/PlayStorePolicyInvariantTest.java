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
        // Gradle :app tests normally use <repo>/app as user.dir.
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
    public void manifestDoesNotDeclareBroadMediaOrFullScreenPermissions() throws Exception {
        String manifest = readFromRepo("app/src/main/AndroidManifest.xml");
        String[] forbidden = {
                "android.permission.USE_FULL_SCREEN_INTENT",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        for (String permission : forbidden) {
            assertFalse("Forbidden Play Store permission present: " + permission,
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
    public void firebaseServiceDoesNotUseFullScreenIntent() throws Exception {
        String source = readFromRepo(
                "app/src/main/java/com/transiva/app/TransivaFirebaseService.java");
        assertFalse(source.contains("setFullScreenIntent("));
        assertTrue(source.contains("transiva_call_channel_v4"));
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
}
