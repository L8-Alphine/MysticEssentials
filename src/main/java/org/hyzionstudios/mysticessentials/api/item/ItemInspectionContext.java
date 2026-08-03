package org.hyzionstudios.mysticessentials.api.item;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ambient facts about <i>why</i> an item is being inspected, handed to every
 * {@link ItemViewProvider}.
 *
 * <p>Providers use this to decide how much to contribute — an RPG mod may want
 * to mark requirements as satisfied or not when a viewer is known, and to skip
 * that work entirely for a chat capture with no viewer yet.</p>
 */
public final class ItemInspectionContext {

    /** What the resulting view will be used for. */
    public enum Purpose {
        /** Captured because a player shared the item in chat. */
        CHAT_SHARE,
        /** Rendered into the ItemView panel for a viewer. */
        DETAIL_VIEW,
        /** Requested through the public API by another mod. */
        API,
        /** Built for a log line, audit record, or diagnostic dump. */
        DIAGNOSTIC
    }

    private final Purpose purpose;
    private final UUID ownerId;
    private final String ownerName;
    private final UUID viewerId;
    private final String channelId;
    private final String worldName;
    private final Map<String, String> attributes;

    private ItemInspectionContext(Builder builder) {
        this.purpose = builder.purpose == null ? Purpose.API : builder.purpose;
        this.ownerId = builder.ownerId;
        this.ownerName = builder.ownerName;
        this.viewerId = builder.viewerId;
        this.channelId = builder.channelId;
        this.worldName = builder.worldName;
        this.attributes = Map.copyOf(builder.attributes);
    }

    public static Builder builder(Purpose purpose) {
        return new Builder(purpose);
    }

    /** A context with no player or location detail — for API and diagnostic use. */
    public static ItemInspectionContext api() {
        return builder(Purpose.API).build();
    }

    public Purpose purpose() {
        return purpose;
    }

    /** The player who holds or shared the item, if known. */
    public Optional<UUID> ownerId() {
        return Optional.ofNullable(ownerId);
    }

    public Optional<String> ownerName() {
        return Optional.ofNullable(ownerName);
    }

    /**
     * The player the view is being rendered for, if known. Absent during capture,
     * because a shared item is inspected once and shown to many.
     */
    public Optional<UUID> viewerId() {
        return Optional.ofNullable(viewerId);
    }

    public Optional<String> channelId() {
        return Optional.ofNullable(channelId);
    }

    public Optional<String> worldName() {
        return Optional.ofNullable(worldName);
    }

    /** Free-form extras a caller attached; providers should tolerate their absence. */
    public Map<String, String> attributes() {
        return attributes;
    }

    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    /** A copy of this context bound to a specific viewer, for per-viewer rendering. */
    public ItemInspectionContext forViewer(UUID viewer) {
        Builder builder = builder(purpose)
                .owner(ownerId, ownerName)
                .viewer(viewer)
                .channelId(channelId)
                .worldName(worldName);
        attributes.forEach(builder::attribute);
        return builder.build();
    }

    public static final class Builder {
        private final Purpose purpose;
        private UUID ownerId;
        private String ownerName;
        private UUID viewerId;
        private String channelId;
        private String worldName;
        private final Map<String, String> attributes = new java.util.LinkedHashMap<>();

        private Builder(Purpose purpose) {
            this.purpose = purpose;
        }

        public Builder owner(UUID ownerId, String ownerName) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            return this;
        }

        public Builder viewer(UUID viewerId) {
            this.viewerId = viewerId;
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public Builder worldName(String worldName) {
            this.worldName = worldName;
            return this;
        }

        public Builder attribute(String key, String value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        public ItemInspectionContext build() {
            return new ItemInspectionContext(this);
        }
    }
}
