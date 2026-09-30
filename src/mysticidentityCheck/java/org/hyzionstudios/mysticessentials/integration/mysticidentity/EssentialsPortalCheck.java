package org.hyzionstudios.mysticessentials.integration.mysticidentity;

import com.google.gson.JsonParser;
import java.util.UUID;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultItemStack;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock;

/**
 * Dependency-free checks for the MysticIdentity portal adapter, in the style of this project's
 * other verification mains ({@code gradlew verifyPortalAdapter}, part of {@code check}).
 */
public final class EssentialsPortalCheck {

    public static void main(String[] args) {
        vaultsRenderAsAReadOnlyGrid();
        patchMarkupBecomesPlainText();
        nicknamesAreReadFromTheStoredProfile();
        System.out.println("verifyPortalAdapter: vault grid, patch text and stored nickname all hold.");
    }

    private static void vaultsRenderAsAReadOnlyGrid() {
        PlayerVault vault = new PlayerVault(UUID.randomUUID().toString(), 2, 1);
        vault.items.add(new VaultItemStack(0, "hytale:Iron_Ingot", 5, 0, 0, null));
        vault.items.add(new VaultItemStack(3, "hytale:Moonlit_Blade", 1, 212, 250, null));
        // A slot beyond the vault's rows must not widen the grid or appear anywhere.
        vault.items.add(new VaultItemStack(40, "hytale:Stray", 1, 0, 0, null));

        PortalBlock.Section section = (PortalBlock.Section) EssentialsPortalProvider.vaultSection(vault, 9);
        require("Vault 2".equals(section.title()), "an unnamed vault is called by its number: " + section.title());
        require("2 of 9 slots used".equals(section.subtitle()), "usage counts only slots inside the vault: " + section.subtitle());
        PortalBlock.Grid grid = (PortalBlock.Grid) section.blocks().getFirst();
        require(grid.columns() == 9 && grid.slots().size() == 9, "one row of nine");
        require(grid.slots().get(0).quantity() == 5 && !grid.slots().get(0).isEmpty(), "the ingots sit in slot 0");
        require(grid.slots().get(3).detail() != null && grid.slots().get(3).detail().startsWith("Durability"),
                "durability is described: " + grid.slots().get(3).detail());
        require(grid.slots().get(1).isEmpty(), "untouched slots stay empty");

        PlayerVault empty = new PlayerVault(UUID.randomUUID().toString(), 1, 6);
        PortalBlock.Section none = (PortalBlock.Section) EssentialsPortalProvider.vaultSection(empty, 9);
        require(none.blocks().getFirst() instanceof PortalBlock.Empty, "an empty vault says so rather than drawing 54 blanks");
    }

    private static void patchMarkupBecomesPlainText() {
        String plain = EssentialsPortalProvider.plain("## New\n- **Guild banners** at level 10\n`/guild banner`");
        require("New\n• Guild banners at level 10\n/guild banner".equals(plain), "markup stripped, bullets kept: " + plain);
        require(EssentialsPortalProvider.plain("  ") == null, "blank bodies are absent");
    }

    private static void nicknamesAreReadFromTheStoredProfile() {
        require(EssentialsPortalProvider.storedNickname(JsonParser.parseString(
                "{\"username\":\"Alphine\",\"metadata\":{\"nickname\":\"&bAlph\"}}")).orElseThrow().equals("&bAlph"),
                "the raw stored nickname is found");
        require(EssentialsPortalProvider.storedNickname(JsonParser.parseString("{\"username\":\"Alphine\"}")).isEmpty(),
                "a profile without metadata has no nickname");
        require(EssentialsPortalProvider.storedNickname(null).isEmpty(), "a player never stored has no nickname");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
