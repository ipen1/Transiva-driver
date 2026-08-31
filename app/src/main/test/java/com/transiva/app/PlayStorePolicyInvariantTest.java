package com.transiva.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class PlayStorePolicyInvariantTest {
    private static Path projectRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("app/src/main/AndroidManifest.xml"))) return p;
        }
        throw new AssertionError("Project root not found from " + System.getProperty("user.dir"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(projectRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    public void manifestDoesNotRequestBroadMediaOrFullScreenIntent() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("USE_FULL_SCREEN_INTENT"));
        assertFalse(manifest.contains("READ_MEDIA_IMAGES"));
        assertFalse(manifest.contains("READ_MEDIA_VIDEO"));
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
    }

    @Test
    public void incomingCallNeverAttachesFullScreenIntent() throws Exception {
        String firebase = read("app/src/main/java/com/transiva/app/TransivaFirebaseService.java");
        assertFalse(firebase.contains("setFullScreenIntent("));
        assertTrue(firebase.contains("CATEGORY_CALL"));
        assertTrue(firebase.contains("PRIORITY_MAX"));
    }

    @Test
    public void fileSelectionUsesSystemDocumentPicker() throws Exception {
        String chat = read("app/src/main/java/com/transiva/app/DriverChatRoomActivity.java");
        String topup = read("app/src/main/java/com/transiva/app/DriverTopUpActivity.java");
        assertTrue(chat.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(topup.contains("Intent.ACTION_OPEN_DOCUMENT"));
    }

    @Test
    public void backgroundLocationProtectionRemainsDeclared() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("ACCESS_BACKGROUND_LOCATION"));
        assertTrue(manifest.contains("FOREGROUND_SERVICE_LOCATION"));
    }
}
