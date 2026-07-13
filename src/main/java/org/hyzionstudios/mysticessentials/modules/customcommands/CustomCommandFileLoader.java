package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Loads command definitions from
 * {@code mods/MysticEssentials/modules/customcommands/commands/*.json} and
 * generates the safe example set (rules/discord/vote/store — messages,
 * notifications, and one cooldown demo; no console commands) on first
 * startup. A malformed file is logged and skipped, never fatal; owner files
 * are never overwritten.
 */
public final class CustomCommandFileLoader {

    private final MysticCore core;
    private final String moduleId;

    /** Files that failed JSON parsing entirely on the last load (name -> error). */
    private final List<String> fileErrors = new ArrayList<>();

    public CustomCommandFileLoader(MysticCore core, String moduleId) {
        this.core = core;
        this.moduleId = moduleId;
    }

    public Path commandsDir() {
        return core.paths().moduleConfigDir(moduleId).resolve("commands");
    }

    /** Parse failures from the last {@link #load} (for {@code /customcommands validate}). */
    public List<String> fileErrors() {
        return List.copyOf(fileErrors);
    }

    /**
     * Generates examples on first startup (when the commands directory does not
     * exist yet), then loads every definition file. Each definition remembers
     * its source file so enable/disable can write back.
     */
    public List<CustomCommand> load(boolean generateExamples) {
        boolean firstStartup = !Files.isDirectory(commandsDir());
        if (firstStartup) {
            try {
                Files.createDirectories(commandsDir());
                if (generateExamples) {
                    writeExamples();
                }
            } catch (IOException e) {
                core.log(Level.SEVERE, "[" + moduleId + "] Could not create commands directory: "
                        + e.getMessage());
            }
        }

        List<CustomCommand> definitions = new ArrayList<>();
        fileErrors.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(commandsDir(), "*.json")) {
            for (Path file : stream) {
                try {
                    CustomCommand definition = Json.readFile(file, CustomCommand.class);
                    if (definition == null || definition.name == null || definition.name.isBlank()) {
                        fileErrors.add(file.getFileName() + ": missing or blank 'name'");
                        core.log(Level.WARNING, "[" + moduleId + "] Skipping "
                                + file.getFileName() + ": missing or blank 'name'.");
                        continue;
                    }
                    definition.sourceFile = file;
                    definitions.add(definition);
                } catch (Exception e) {
                    fileErrors.add(file.getFileName() + ": " + e.getMessage());
                    core.log(Level.WARNING, "[" + moduleId + "] Skipping invalid JSON file "
                            + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to read " + commandsDir()
                    + ": " + e.getMessage());
        }
        return definitions;
    }

    /** Persists a definition back to its source file (used by enable/disable). */
    public boolean saveDefinition(CustomCommand definition) {
        if (definition.sourceFile == null) {
            return false;
        }
        try {
            Json.writeFile(definition.sourceFile, definition);
            return true;
        } catch (IOException e) {
            core.log(Level.WARNING, "[" + moduleId + "] Could not save "
                    + definition.sourceFile.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    // ----- Example generation ------------------------------------------------------

    private void writeExamples() {
        writeExample("rules.json", exampleRules());
        writeExample("discord.json", exampleDiscord());
        writeExample("vote.json", exampleVote());
        writeExample("store.json", exampleStore());
        core.log(Level.INFO, "[" + moduleId + "] Generated example commands"
                + " (rules, discord, vote, store) in " + commandsDir());
    }

    private void writeExample(String fileName, CustomCommand definition) {
        Path file = commandsDir().resolve(fileName);
        try {
            Json.writeFile(file, definition);
        } catch (IOException e) {
            core.log(Level.WARNING, "[" + moduleId + "] Could not write example "
                    + fileName + ": " + e.getMessage());
        }
    }

    private static CustomCommand exampleRules() {
        CustomCommand command = new CustomCommand();
        command.name = "rules";
        command.description = "Shows the server rules.";
        command.aliases.add("serverrules");
        command.actions.add(messageLines(
                "&d&lServer Rules",
                "&71. &fBe respectful to other players.",
                "&72. &fNo cheating, exploiting, or griefing.",
                "&73. &fKeep chat friendly - no spam or advertising.",
                "&74. &fReport problems to staff instead of taking revenge.",
                "&75. &fHave fun, {player_name}!"));
        return command;
    }

    private static CustomCommand exampleDiscord() {
        CustomCommand command = new CustomCommand();
        command.name = "discord";
        command.description = "Shows the community Discord invite.";
        command.actions.add(messageLines(
                "&9&lDiscord",
                "&7Join our community: &b<link:https://discord.gg/your-invite>discord.gg/your-invite</link>"));
        return command;
    }

    private static CustomCommand exampleVote() {
        CustomCommand command = new CustomCommand();
        command.name = "vote";
        command.description = "Shows the voting sites.";
        // Cooldown demo: a friendly repeat-use limit with the standard bypass nodes.
        command.cooldown.seconds = 30;
        command.cooldown.message = "&cYou checked the vote list moments ago"
                + " - try again in {cooldown_remaining}s.";
        command.actions.add(messageLines(
                "&a&lVote for us!",
                "&71. &f<link:https://example.com/vote1>example.com/vote1</link>",
                "&72. &f<link:https://example.com/vote2>example.com/vote2</link>"));
        JsonObject notification = new JsonObject();
        notification.addProperty("type", "notification");
        notification.addProperty("title", "&aThanks for supporting the server!");
        notification.addProperty("style", "success");
        command.actions.add(notification);
        return command;
    }

    private static CustomCommand exampleStore() {
        CustomCommand command = new CustomCommand();
        command.name = "store";
        command.description = "Shows the server store link.";
        command.aliases.add("shop");
        command.aliases.add("donate");
        command.actions.add(messageLines(
                "&6&lServer Store",
                "&7Support the server and get perks:",
                "&e<link:https://store.example.com>store.example.com</link>"));
        return command;
    }

    private static JsonObject messageLines(String... lines) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "message");
        JsonArray array = new JsonArray();
        for (String line : lines) {
            array.add(line);
        }
        action.add("lines", array);
        return action;
    }
}
