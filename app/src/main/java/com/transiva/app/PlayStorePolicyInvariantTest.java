package com.transiva.app;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class PlayStorePolicyInvariantTest {
    private String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        fail("Project file not found: " + relativePath);
        return "";
    }

    @Test
    public void manifestAndFcmUsePlaySafeIncomingCallFullScreenFlow() throws Exception {
        String manifest = readProjectFile("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android.permission.USE_FULL_SCREEN_INTENT"));
        assertFalse(manifest.contains("android.permission.READ_MEDIA_IMAGES"));
        assertFalse(manifest.contains("android.permission.READ_MEDIA_VIDEO"));
        assertFalse(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"));

        String source = readProjectFile("app/src/main/java/com/transiva/app/TransivaFirebaseService.java");
        assertTrue(source.contains("transiva_call_channel_v4"));
        assertTrue(source.contains("incomingCallNotification"));
        assertTrue(source.contains("canUseFullScreenCallIntent()"));
        assertTrue(source.contains("manager.canUseFullScreenIntent()"));
        assertTrue(source.contains("setFullScreenIntent("));
    }
}
