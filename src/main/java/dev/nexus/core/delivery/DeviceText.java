package dev.nexus.core.delivery;

import java.nio.charset.StandardCharsets;

/** Limits are UTF-8 bytes, shared with the small device's fixed-size fields. */
public final class DeviceText {
    private DeviceText() {}
    public static String fit(String input, int bytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isISOControl(cp)) cp = ' ';
            String character = new String(Character.toChars(cp));
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + length > bytes) break;
            result.append(character);
            used += length;
        }
        return result.toString();
    }
}
