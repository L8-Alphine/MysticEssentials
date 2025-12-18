package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.server.level.ServerPlayer;

public final class VaultTextSanitizer {
    private VaultTextSanitizer(){}

    public static String sanitizeVaultBaseName(ServerPlayer p, String input) {
        if (input == null) return "Vault";
        String s = input.trim();
        if (s.isEmpty()) return "Vault";

        // hard length cap
        if (s.length() > 32) s = s.substring(0, 32);

        // apply legacy & codes only if enabled + permitted per-code
        if (MEConfig.INSTANCE.vaults.allowLegacyInRenameIfPermitted) {
            s = filterLegacyCodes(p, s);
        } else {
            s = s.replace("&", "");
        }

        // hex support (optional): require format.x or a dedicated node you choose
        if (!MEConfig.INSTANCE.vaults.allowHexInRenameIfPermitted) {
            // strip common hex patterns like &#RRGGBB
            s = s.replaceAll("(?i)&#[0-9a-f]{6}", "");
        } else {
            // If you want a real permission for hex, use format.x:
            if (!Perms.has(p, PermNodes.vaultRenameFormatNode('x'), 0)) {
                s = s.replaceAll("(?i)&#[0-9a-f]{6}", "");
            }
        }

        return s;
    }

    private static String filterLegacyCodes(ServerPlayer p, String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));

                boolean isColor = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
                boolean isFormat = "klmnor".indexOf(code) >= 0;

                if (isColor) {
                    if (Perms.has(p, PermNodes.vaultRenameColorNode(code), 0)) {
                        out.append('&').append(code);
                    }
                    i++;
                    continue;
                }

                if (isFormat) {
                    if (Perms.has(p, PermNodes.vaultRenameFormatNode(code), 0)) {
                        out.append('&').append(code);
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
