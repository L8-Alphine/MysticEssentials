package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import java.util.Locale;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;

/**
 * Plays a 2D sound event to the sender (or everyone online), using the
 * verified 0.5.6 path — {@code SoundEvent.getAssetMap().getIndex(name)} plus
 * {@code SoundUtil.playSoundEvent2dToPlayer} — the same resolution the vanilla
 * {@code /sound play2d} command performs.
 *
 * <pre>
 * { "type": "sound", "sound": "SFX_UI_Notification",
 *   "category": "sfx", "volume": 1.0, "pitch": 1.0, "target": "sender" }
 * </pre>
 *
 * <p>Sound event names come from the asset catalog; an unknown name is logged
 * once per execution and skipped (asset packs differ between servers, so this
 * is a runtime lookup by design). Categories: {@code music}, {@code ambient},
 * {@code sfx}, {@code ui}, {@code voice}.</p>
 */
public final class SoundAction implements CustomCommandAction {

    private final String soundEvent;
    private final String category;
    private final float volume;
    private final float pitch;
    private final boolean toAll;

    public SoundAction(String soundEvent, String category, float volume, float pitch, boolean toAll) {
        this.soundEvent = soundEvent;
        this.category = category;
        this.volume = volume;
        this.pitch = pitch;
        this.toAll = toAll;
    }

    /** @return {@code true} if {@code category} maps to a known {@link SoundCategory}. */
    public static boolean isKnownCategory(String category) {
        return category == null || category.isBlank() || parseCategory(category) != null;
    }

    private static SoundCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return SoundCategory.SFX;
        }
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "music" -> SoundCategory.Music;
            case "ambient" -> SoundCategory.Ambient;
            case "sfx" -> SoundCategory.SFX;
            case "ui" -> SoundCategory.UI;
            case "voice" -> SoundCategory.Voice;
            default -> null;
        };
    }

    @Override
    public String type() {
        return "sound";
    }

    @Override
    public void execute(CustomCommandContext context) {
        SoundCategory soundCategory = parseCategory(category);
        if (soundCategory == null) {
            soundCategory = SoundCategory.SFX;
        }
        int index;
        try {
            index = SoundEvent.getAssetMap().getIndexOrDefault(soundEvent, -1);
        } catch (Throwable t) {
            context.log(Level.WARNING, "Sound asset lookup failed for '" + soundEvent + "': " + t);
            return;
        }
        if (index < 0) {
            context.log(Level.WARNING, "Unknown sound event '" + soundEvent
                    + "' in command '" + context.commandName() + "'; skipping sound action.");
            return;
        }
        if (toAll) {
            for (PlayerRef online : context.onlinePlayers()) {
                play(online, index, soundCategory);
            }
            return;
        }
        PlayerRef player = context.player();
        if (player != null) {
            play(player, index, soundCategory);
        }
    }

    private void play(PlayerRef player, int index, SoundCategory soundCategory) {
        try {
            SoundUtil.playSoundEvent2dToPlayer(player, index, soundCategory, volume, pitch);
        } catch (Throwable t) {
            // Never let a sound failure break the rest of the chain.
        }
    }

    @Override
    public String describe() {
        return "sound [" + (toAll ? "all" : "sender") + "]: " + soundEvent;
    }
}
