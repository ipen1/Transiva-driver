package com.transiva.app;

import android.app.Activity;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatPhoneNotice {
    private static final Pattern NUMBERS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:[+＋]\\s*)?\\p{Nd}(?:[\\s\\p{Cf}()./_-]*\\p{Nd}){6,17}(?![\\p{L}\\p{N}])");
    private static final Pattern CONTEXT = Pattern.compile("(?iu)\\b(?:nomor|no\\.?\\s*(?:hp|wa)|telepon|telp|kontak|whatsapp)\\b");

    private static final Pattern SPOKEN = Pattern.compile(
            "(?iu)\\b(?:nol|kosong|zero)[\\s,.-]+(?:delapan|eight)"
                    + "(?:[\\s,.-]+(?:nol|kosong|satu|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan"
                    + "|zero|one|two|three|four|five|six|seven|eight|nine|\\p{Nd})){6,13}\\b");

    private ChatPhoneNotice() {}

    static boolean detectsPhone(String message) {
        Matcher candidates = NUMBERS.matcher(message);
        while (candidates.find()) {
            String candidate = candidates.group();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < candidate.length(); i++) {
                int digit = Character.digit(candidate.charAt(i), 10);
                if (digit >= 0) digits.append(digit);
            }
            String number = digits.toString();
            if (number.length() >= 8 && (number.startsWith("08") || number.startsWith("62")
                    || number.startsWith("00") || candidate.startsWith("+") || candidate.startsWith("＋")
                    || (number.startsWith("0") && number.length() >= 9))) return true;
            if (CONTEXT.matcher(message).find()) return true;
        }
        return SPOKEN.matcher(message).find();
    }

    static void confirm(Activity activity, Runnable send) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Kirim nomor telepon?")
                .setMessage("Nomor ini akan terlihat oleh lawan chat. Gunakan untuk koordinasi pesanan. Memindahkan transaksi ke luar Transiva dapat menyebabkan pembatasan akun.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Tetap kirim", (dialog, which) -> send.run())
                .show();
    }
}
