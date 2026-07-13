package org.hyzionstudios.mysticessentials.modules.playervaults.ui;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

/**
 * A vault {@link ContainerWindow} that lights up the client's native right-side
 * info panel (the "RESOURCE STORAGE - FULL" chrome beside a chest grid).
 *
 * <p>That panel is entirely client-rendered from the {@code windowData} JSON the
 * server ships in the {@code OpenWindow} packet. The only key the client resolves
 * for it is <b>{@code blockItemId}</b> — it looks that item id up and draws the
 * item's localized name, 3D icon, and description. There is <i>no</i> free-text
 * title field, so the panel shows the icon item's own name, not the vault's custom
 * name/number (that lives on the vault-list dashboard and the open message).</p>
 *
 * <p>The base {@code ContainerWindow} builds an empty {@code windowData}, which is
 * why an unadorned vault chest shows no side panel. We can't reach that private
 * field, so we keep our own stable {@link JsonObject} and return it from
 * {@link #getData()}. It <b>must</b> be a stable instance: the engine's
 * {@code setNeedRebuild}/{@code consumeNeedRebuild} call {@code getData()} and
 * add/remove the {@code needRebuild} flag on the returned object, and the packet
 * serializer ({@code WindowManager.openWindow}) reads {@code getData()} virtually —
 * returning a fresh object per call would drop the rebuild flag.</p>
 */
public final class VaultContainerWindow extends ContainerWindow {

    private final JsonObject windowData = new JsonObject();

    /**
     * @param container   the backing vault grid
     * @param blockItemId item id whose name/icon/description the side panel shows;
     *                    blank/{@code null} leaves the panel off (empty windowData)
     */
    public VaultContainerWindow(ItemContainer container, String blockItemId) {
        super(container);
        if (blockItemId != null && !blockItemId.isBlank()) {
            windowData.addProperty("blockItemId", blockItemId);
        }
    }

    @Override
    public JsonObject getData() {
        return windowData;
    }
}
