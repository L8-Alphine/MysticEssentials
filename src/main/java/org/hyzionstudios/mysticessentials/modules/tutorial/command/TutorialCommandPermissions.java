package org.hyzionstudios.mysticessentials.modules.tutorial.command;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Permission gate for the tutorial command tree. All nodes live in the central
 * {@link Permissions} registry; this adds the tutorial rule that
 * {@code mysticessentials.tutorial.admin} grants every tutorial node.
 */
public final class TutorialCommandPermissions {

    public static final String ADMIN = Permissions.TUTORIAL_ADMIN;
    public static final String LIST = Permissions.TUTORIAL_LIST;
    public static final String INFO = Permissions.TUTORIAL_INFO;
    public static final String PLAY = Permissions.TUTORIAL_PLAY;
    public static final String PLAY_OTHERS = Permissions.TUTORIAL_PLAY_OTHERS;
    public static final String STOP = Permissions.TUTORIAL_STOP;
    public static final String STOP_OTHERS = Permissions.TUTORIAL_STOP_OTHERS;
    public static final String SKIP = Permissions.TUTORIAL_SKIP;
    public static final String SKIP_OTHERS = Permissions.TUTORIAL_SKIP_OTHERS;
    public static final String RESET = Permissions.TUTORIAL_RESET;
    public static final String COMPLETE = Permissions.TUTORIAL_COMPLETE;
    public static final String STATUS = Permissions.TUTORIAL_STATUS;
    public static final String STATUS_OTHERS = Permissions.TUTORIAL_STATUS_OTHERS;
    public static final String PAGE = Permissions.TUTORIAL_PAGE;
    public static final String PAGE_OTHERS = Permissions.TUTORIAL_PAGE_OTHERS;
    public static final String RELOAD = Permissions.TUTORIAL_RELOAD;
    public static final String DEBUG = Permissions.TUTORIAL_DEBUG;
    public static final String SCENE = Permissions.TUTORIAL_SCENE;
    public static final String BYPASS_FIRSTJOIN = Permissions.TUTORIAL_BYPASS_FIRSTJOIN;

    private TutorialCommandPermissions() {
    }

    /** @return whether the sender holds {@code node} or the tutorial admin node. */
    public static boolean has(MysticCommandSender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(ADMIN);
    }

    /** As {@link #has(MysticCommandSender, String)} for a player target. */
    public static boolean has(PlayerRef player, String node) {
        return player.hasPermission(node) || player.hasPermission(ADMIN);
    }
}
