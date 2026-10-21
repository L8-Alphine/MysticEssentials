package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditQuery;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.SimulationReport;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.TemplateLibrary;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.TemplateApply;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

/**
 * Engine-free checks of the Phase 2 screens track (P2-H): the Simulation
 * screen's rows, bars and small-sample warnings; the Audit History's filters,
 * newest-first paging, bounded scans and export formats (CSV escaping
 * included); and the Template Library's snapshots, applying a key look,
 * inserting a reward set with colliding ids, and its optimistic template
 * store.
 */
public final class LootBoxScreensTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final OddsFormatter FORMATTER = new OddsFormatter(2, 8, Locale.US);

    /** Storage and file work run here, as they would off the world thread. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "screens-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxScreensTest() {
    }

    public static void main(String[] args) throws IOException {
        try {
            simulationRowsMatchTheOdds();
            simulationWarnsOnSmallSamples();
            simulationJudgesTheGap();
            auditBucketsGroupByHour();
            auditFiltersMatch();
            auditPagesNewestFirstAndStayBounded();
            auditExportsEscapeAndCap();
            templateSnapshotsAreCopies();
            keyLookApplies();
            rewardSetInsertRenamesCollisions();
            crateLookCopiesOnlyTheLook();
            templateRulesAndIds();
            templateStoreIsOptimistic();
            applyingWritesWithTheLoadedHash();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxScreensTest: all checks passed.");
    }

    // ----- Simulation -----------------------------------------------------------

    private static void simulationRowsMatchTheOdds() {
        LootBoxRevision revision = revision(6000, 3000, 999, 1);
        OddsTable table = OddsEngine.table(revision, "FREE_GAMEPLAY_ONLY");
        SimulationReport report = SimulationReport.of(revision, table, new long[] {6000, 3000, 999, 1}, FORMATTER,
                1_000_000);
        require(report.draws() == 10_000, "the draws are the counts' sum, not " + report.draws());
        require(report.rows().size() == 4, "one row per reward");
        for (int i = 0; i < 4; i++) {
            SimulationReport.Row row = report.rows().get(i);
            OddsTable.Entry entry = table.entries().get(i);
            require(row.chance().equals(FORMATTER.percent(entry.weight(), table.total())),
                    row.rewardId() + "'s chance prints as the preview's: " + row.chance());
            require(Math.abs(row.gap()) < 1e-9, row.rewardId() + " drew exactly its share, gap " + row.gap());
        }
        SimulationReport.Row common = report.rows().get(0);
        SimulationReport.Row rare = report.rows().get(3);
        require(common.theoryBar() == SimulationReport.BAR_WIDTH && common.observedBar() == SimulationReport.BAR_WIDTH,
                "the largest share fills the bar");
        require(report.rows().get(1).theoryBar() == SimulationReport.BAR_WIDTH / 2,
                "half the largest share is half a bar, not " + report.rows().get(1).theoryBar());
        require(rare.theoryBar() == SimulationReport.MIN_BAR, "a tiny share still shows a sliver");
        require(common.observedText().equals("60.0000%"), "observed prints with four places: " + common.observedText());
        require(common.countText().equals("6,000 · exp. 6,000"), "count line: " + common.countText());
        require(rare.countText().equals("1 · exp. 1.0"), "a small expected count keeps a decimal: " + rare.countText());
        require(common.gapText().equals("&a+0.0000"), "a gap within noise is green: " + common.gapText());

        SimulationReport none = SimulationReport.of(revision, table, new long[] {6000, 3000, 1000, 0}, FORMATTER,
                1_000_000);
        require(none.rows().get(3).observedBar() == 0, "a reward never drawn has no observed bar");
        require(none.rows().get(3).theoryBar() == SimulationReport.MIN_BAR, "but its chance still shows");
    }

    private static void simulationWarnsOnSmallSamples() {
        LootBoxRevision revision = revision(6000, 3000, 999, 1);
        OddsTable table = OddsEngine.table(revision, "FREE_GAMEPLAY_ONLY");
        SimulationReport small = SimulationReport.of(revision, table, new long[] {6000, 3000, 999, 1}, FORMATTER,
                1_000_000);
        require(small.rows().get(3).verdict() == SimulationReport.Verdict.FEW_DRAWS,
                "a reward expected once gets no verdict");
        require(small.rows().get(3).gapText().endsWith("few draws"), "and says why: " + small.rows().get(3).gapText());
        require(small.warnings().size() == 1 && small.warnings().get(0).contains("Too few draws")
                        && small.warnings().get(0).contains("Run at least &f100,000"),
                "the rarest reward expected under 10 times warns and says how many draws: " + small.warnings());
        require(SimulationReport.drawsFor(1, 10_000) == 100_000, "10 expected hits of 1 in 10,000 need 100,000 draws");

        SimulationReport capped = SimulationReport.of(revision, table, new long[] {6000, 3000, 999, 1}, FORMATTER,
                50_000);
        require(capped.warnings().get(0).contains("Even the maximum of &f50,000"),
                "when the cap cannot reach 10 expected hits it says so: " + capped.warnings());

        SimulationReport enough = SimulationReport.of(revision, table, new long[] {600_000, 300_000, 99_900, 100},
                FORMATTER, 1_000_000);
        require(enough.warnings().isEmpty(), "100 expected hits need no warning: " + enough.warnings());
        require(enough.rows().get(3).verdict() == SimulationReport.Verdict.WITHIN, "and get a verdict");
    }

    private static void simulationJudgesTheGap() {
        LootBoxRevision revision = revision(6000, 3000, 1000);
        OddsTable table = OddsEngine.table(revision, "FREE_GAMEPLAY_ONLY");
        // se(a) = sqrt(.6 * .4 / 10000) = 0.0049: +2.5 se is 6123 hits.
        SimulationReport watch = SimulationReport.of(revision, table, new long[] {6123, 2877, 1000}, FORMATTER,
                1_000_000);
        require(watch.rows().get(0).verdict() == SimulationReport.Verdict.WATCH, "2.5 se is worth a second run: "
                + watch.rows().get(0).sigmas());
        require(watch.rows().get(0).gapText().startsWith("&e+1.2300"), "yellow, in points: "
                + watch.rows().get(0).gapText());
        require(watch.rows().get(2).verdict() == SimulationReport.Verdict.WITHIN, "an exact share is within noise");
        require(watch.warnings().isEmpty(), "WATCH alone does not warn");

        SimulationReport unusual = SimulationReport.of(revision, table, new long[] {6200, 2800, 1000}, FORMATTER,
                1_000_000);
        require(unusual.rows().get(0).verdict() == SimulationReport.Verdict.UNUSUAL
                && unusual.rows().get(1).verdict() == SimulationReport.Verdict.UNUSUAL, "beyond 3 se is unusual");
        require(unusual.rows().get(1).gapText().startsWith("&c-2.0000"), "red and signed: "
                + unusual.rows().get(1).gapText());
        require(unusual.warnings().size() == 1 && unusual.warnings().get(0).startsWith("&e2 reward(s)"),
                "unusual gaps are counted in a warning: " + unusual.warnings());
    }

    // ----- Audit ----------------------------------------------------------------

    private static final long H14 = Instant.parse("2026-10-20T14:00:00Z").toEpochMilli();
    private static final long MINUTE = 60_000L;

    /** Three hours on two servers, plus keys that are not buckets. */
    private static InMemoryAudit fixture() {
        InMemoryAudit audit = new InMemoryAudit();
        audit.add("2026102012-lobby", entry(H14 - 2 * 60 * MINUTE + MINUTE, "lobby", "steve-uuid", "OPEN_COMMIT",
                "op1", "royal@3 reward gem"));
        audit.add("2026102013-lobby", entry(H14 - 60 * MINUTE + 5 * MINUTE, "lobby", "alice", "PUBLISH", "royal",
                "revision 3"));
        audit.add("2026102013-lobby", entry(H14 - 60 * MINUTE + 9 * MINUTE, "lobby", "alice", "KEY_GRANT",
                "steve-uuid", "mystic:royal x2"));
        audit.add("2026102014-lobby", entry(H14 + MINUTE, "lobby", "steve-uuid", "OPEN_REQUEST", "op2",
                "royal@3"));
        audit.add("2026102014-lobby", entry(H14 + 3 * MINUTE, "lobby", "steve-uuid", "OPEN_COMMIT", "op2",
                "royal@3 reward coin, \"lucky\""));
        audit.add("2026102014-hub2", entry(H14 + 2 * MINUTE, "hub2", "server", "DELIVERED", "op9#0", "DELIVERED"));
        audit.add("2026102014-hub2", entry(H14 + 4 * MINUTE, "hub2", "console", "KEY_REVOKE", "SERIAL1",
                "=cmd|' /C calc'!A0"));
        audit.keys.add("not-a-bucket");
        audit.keys.add("2026102");
        return audit;
    }

    private static void auditBucketsGroupByHour() {
        List<String> keys = List.of("2026102013-lobby", "2026102014-hub2", "junk", "2026102014-lobby",
                "2026102012-lobby", "20261020xx-lobby");
        List<List<String>> hours = AuditQuery.hours(keys, 0, Long.MAX_VALUE);
        require(hours.size() == 3, "three hours, malformed keys skipped: " + hours);
        require(hours.get(0).equals(List.of("2026102014-hub2", "2026102014-lobby")), "newest hour first, its servers"
                + " together: " + hours.get(0));
        require(hours.get(2).equals(List.of("2026102012-lobby")), "oldest hour last");
        List<List<String>> window = AuditQuery.hours(keys, H14 - 30 * MINUTE, Long.MAX_VALUE);
        require(window.size() == 2, "a window starting at 13:30 keeps the 13:00 bucket and later: " + window);
    }

    private static void auditFiltersMatch() {
        List<AuditQuery.Record> all = all(fixture(), AuditQuery.Filter.everything());
        require(all.size() == 7, "every record: " + all.size());
        require(all.get(0).action().equals("KEY_REVOKE") && all.get(1).action().equals("OPEN_COMMIT")
                        && all.get(2).action().equals("DELIVERED") && all.get(3).action().equals("OPEN_REQUEST"),
                "records of one hour merge across servers by time: " + all.subList(0, 4));

        List<AuditQuery.Record> royal = all(fixture(), new AuditQuery.Filter("ROYAL", "", Set.of(), AuditQuery.ALL,
                0, Long.MAX_VALUE));
        require(royal.size() == 5, "the pool filter matches targets and details, ignoring case: " + royal.size());

        List<AuditQuery.Record> steve = all(fixture(), new AuditQuery.Filter("", "steve-name", Set.of("steve-uuid"),
                AuditQuery.ALL, 0, Long.MAX_VALUE));
        require(steve.size() == 4, "a player's resolved id matches as actor and as grant target: " + steve.size());
        require(steve.stream().anyMatch(r -> r.action().equals("KEY_GRANT")), "including the key grant to them");

        List<AuditQuery.Record> keys = all(fixture(), new AuditQuery.Filter("", "", Set.of(), "group:KEYS", 0,
                Long.MAX_VALUE));
        require(keys.size() == 2, "the KEYS group: " + keys);
        List<AuditQuery.Record> commits = all(fixture(), new AuditQuery.Filter("", "", Set.of(), "open_commit", 0,
                Long.MAX_VALUE));
        require(commits.size() == 2, "an exact action, ignoring case: " + commits.size());
        List<AuditQuery.Record> recent = all(fixture(), new AuditQuery.Filter("", "", Set.of(), AuditQuery.ALL,
                H14 + 2 * MINUTE, Long.MAX_VALUE));
        require(recent.size() == 3, "the time window is exact to the record, not the bucket: " + recent.size());
        require(new AuditQuery.Filter("", "", Set.of(), "group:NOPE", 0, Long.MAX_VALUE)
                .test(all.get(0)) == false, "an unknown group matches nothing");
        require(AuditQuery.GROUPS.values().stream().mapToLong(List::size).sum()
                == AuditQuery.GROUPS.values().stream().flatMap(List::stream).distinct().count(),
                "every action is in one group only");
    }

    private static void auditPagesNewestFirstAndStayBounded() {
        InMemoryAudit audit = fixture();
        AuditQuery.Page first = await(AuditQuery.page(audit, AuditQuery.Filter.everything(), 0, 3, 100, IO));
        require(first.records().size() == 3 && first.more() && first.offset() == 0, "page 1: 3 of 7, more: " + first);
        require(audit.loaded.size() == 2, "page 1 read only the newest hour's 2 buckets: " + audit.loaded);
        AuditQuery.Page second = await(AuditQuery.page(audit, AuditQuery.Filter.everything(), 3, 3, 100, IO));
        require(second.records().size() == 3 && second.more(), "page 2: 3 more: " + second);
        require(second.records().get(0).action().equals("OPEN_REQUEST"), "page 2 starts after page 1");
        AuditQuery.Page third = await(AuditQuery.page(audit, AuditQuery.Filter.everything(), 6, 3, 100, IO));
        require(third.records().size() == 1 && !third.more() && !third.truncated(), "page 3: the last one: " + third);

        AuditQuery.Page capped = await(AuditQuery.page(fixture(), AuditQuery.Filter.everything(), 6, 3, 3, IO));
        require(capped.records().isEmpty() && capped.truncated() && capped.scanned() == 3,
                "a scan stops at its bucket limit and says older records may exist: " + capped);

        InMemoryAudit big = new InMemoryAudit();
        for (int hour = 0; hour < 2000; hour++) {
            long at = H14 - hour * 60 * MINUTE;
            big.add(AuditQuery.HOUR.format(Instant.ofEpochMilli(at)) + "-local", entry(at, "local", "a", "PUBLISH",
                    "p" + hour, ""));
        }
        AuditQuery.Page deep = await(AuditQuery.page(big, new AuditQuery.Filter("nothing", "", Set.of(),
                AuditQuery.ALL, 0, Long.MAX_VALUE), 0, 10, 744, IO));
        require(deep.records().isEmpty() && deep.truncated() && big.loaded.size() == 744,
                "a filter that matches nothing reads 744 buckets, not 2000, without a deep stack: " + big.loaded.size());
    }

    private static void auditExportsEscapeAndCap() throws IOException {
        require(AuditQuery.csv("plain").equals("plain"), "plain text is not quoted");
        require(AuditQuery.csv("a,b").equals("\"a,b\""), "a comma is quoted");
        require(AuditQuery.csv("say \"hi\"").equals("\"say \"\"hi\"\"\""), "quotes are doubled");
        require(AuditQuery.csv("two\nlines").equals("\"two\nlines\""), "a newline is quoted");
        require(AuditQuery.csv("=1+1").equals("'=1+1"), "a formula is defused: " + AuditQuery.csv("=1+1"));
        require(AuditQuery.csv("@SUM(A1)").equals("'@SUM(A1)") && AuditQuery.csv("+1").equals("'+1")
                && AuditQuery.csv("-1").equals("'-1"), "every formula prefix is defused");
        require(AuditQuery.csv("=a,b").equals("\"'=a,b\""), "defused and quoted");
        require(AuditQuery.csv(null).isEmpty(), "null is empty");

        List<String> lines = new ArrayList<>();
        AuditQuery.Written written = await(AuditQuery.export(fixture(), AuditQuery.Filter.everything(), 100, 100, IO,
                record -> lines.add(AuditQuery.csvLine(record))));
        require(written.records() == 7 && !written.capped() && lines.size() == 7, "every record exported: " + written);
        require(lines.get(0).equals("2026-10-20 14:04:00," + (H14 + 4 * MINUTE)
                + ",hub2,console,KEY_REVOKE,SERIAL1,'=cmd|' /C calc'!A0\r\n"), "CSV line: " + lines.get(0));
        require(lines.get(1).endsWith(",\"royal@3 reward coin, \"\"lucky\"\"\"\r\n"), "quoted detail: " + lines.get(1));
        require(AuditQuery.CSV_HEADER.startsWith("time_utc,epoch_ms,server,actor,action,target,detail"),
                "the header names every column");

        AuditQuery.Written capped = await(AuditQuery.export(fixture(), AuditQuery.Filter.everything(), 4, 100, IO,
                record -> {
                }));
        require(capped.records() == 4 && capped.capped(), "an export stops at its record limit: " + capped);

        StringWriter out = new StringWriter();
        JsonWriter json = AuditQuery.jsonWriter(out);
        json.beginArray();
        await(AuditQuery.export(fixture(), new AuditQuery.Filter("", "", Set.of(), "PUBLISH", 0, Long.MAX_VALUE),
                100, 100, IO, record -> AuditQuery.json(json, record)));
        json.endArray();
        json.flush();
        JsonArray parsed = Json.parse(out.toString()).getAsJsonArray();
        require(parsed.size() == 1, "JSON export holds the filtered records: " + out);
        JsonObject publish = parsed.get(0).getAsJsonObject();
        require(publish.get("action").getAsString().equals("PUBLISH") && publish.get("target").getAsString().equals("royal")
                && publish.get("time").getAsString().equals("2026-10-20T13:05:00Z")
                && publish.get("at").getAsLong() == H14 - 55 * MINUTE, "JSON fields: " + publish);

        AuditQuery.Written failed = null;
        try {
            failed = await(AuditQuery.export(fixture(), AuditQuery.Filter.everything(), 100, 100, IO, record -> {
                throw new IOException("disk full");
            }));
        } catch (AssertionError expected) {
            require(String.valueOf(expected.getCause()).contains("disk full"), "a write failure fails the export");
        }
        require(failed == null, "a failed write does not report success");
    }

    // ----- Templates ------------------------------------------------------------

    private static void templateSnapshotsAreCopies() {
        KeyDraft key = key("mystic:royal_key", "Ingredient_Bar_Gold", "&6Royal Key", List.of("&7Opens", "&7royal"), 3, 7);
        LootBoxTemplate look = TemplateLibrary.keyLook("royal_look", " Royal look ", key, "alice", 123L);
        require(look.kind.equals(LootBoxTemplate.KEY_LOOK) && look.name.equals("Royal look")
                && look.source.equals("key mystic:royal_key") && look.createdBy.equals("alice") && look.createdAt == 123L,
                "a key look records where it came from");
        require(look.key.baseItemId.equals("Ingredient_Bar_Gold") && look.key.name.equals("&6Royal Key")
                && look.key.lore.equals(List.of("&7Opens", "&7royal")) && look.key.uses == 3
                && look.key.expiresAfterDays == 7, "a key look copies base item, name, lore, uses and expiry");
        key.lore.add("&7later");
        require(look.key.lore.size() == 2, "the look is a copy, not a view of the draft");
        require(!Json.toTree(look.key).getAsJsonObject().has("id"), "the key's id is not part of its look");

        LootBoxDraft pool = pool("royal", "EXACT_PERCENT", reward("gem", "50"), reward("coin", "50"));
        LootBoxTemplate set = TemplateLibrary.rewardSet("royal_rewards", "Royal set", pool, "alice", 1L);
        require(set.rewards.size() == 2 && set.rewardMode.equals("EXACT_PERCENT") && set.source.equals("pool royal"),
                "a reward set copies every reward and the mode");
        pool.rewards.get(0).name = "changed";
        require(set.rewards.get(0).name.equals("gem"), "a reward set is a deep copy");
    }

    private static void keyLookApplies() {
        LootBoxTemplate look = TemplateLibrary.keyLook("gold_look", "Gold", key("mystic:a", "Ingredient_Bar_Gold",
                "&6Gold", List.of("&7Shiny"), 5, 30), "alice", 1L);
        KeyDraft target = key("mystic:b", "Ingredient_Bar_Iron", "&7Iron", List.of(), 1, 0);
        KeyDraft applied = TemplateApply.keyLook(look, target);
        require(applied.id.equals("mystic:b"), "applying keeps the target's id");
        require(applied.baseItemId.equals("Ingredient_Bar_Gold") && applied.name.equals("&6Gold")
                && applied.lore.equals(List.of("&7Shiny")) && applied.uses == 5 && applied.expiresAfterDays == 30,
                "applying replaces the look");
        require(target.baseItemId.equals("Ingredient_Bar_Iron"), "the target itself is untouched until written");
        List<String> lines = TemplateApply.keyLookLines(target, applied);
        require(lines.stream().anyMatch(l -> l.contains("Base item") && l.contains("Ingredient_Bar_Iron")
                && l.contains("Ingredient_Bar_Gold")), "the confirmation shows old → new: " + lines);
        require(lines.stream().anyMatch(l -> l.contains("Uses")), "and the uses change: " + lines);
        List<String> same = TemplateApply.keyLookLines(applied, TemplateApply.keyLook(look, applied));
        require(same.size() == 1 && same.get(0).contains("Nothing changes"), "a no-op says so: " + same);
    }

    private static void rewardSetInsertRenamesCollisions() {
        LootBoxDraft source = pool("src", "WEIGHTED", weighted("gem", 10), weighted("coin", 90), weighted("bonus", 5));
        LootBoxTemplate set = TemplateLibrary.rewardSet("src_rewards", "Src", source, "alice", 1L);
        LootBoxDraft target = pool("dest", "WEIGHTED", weighted("gem", 1), weighted("coin", 1), weighted("coin_2", 1));
        TemplateApply.Insertion insertion = TemplateApply.rewardSet(set, target);
        require(target.rewards.size() == 3, "the target is untouched until written");
        LootBoxDraft result = insertion.pool();
        require(result.rewards.size() == 6, "appended after the existing rewards: " + result.rewards.size());
        require(insertion.added().equals(List.of("gem_2", "coin_3", "bonus")),
                "taken ids follow RewardsTab.nextId, free ones are kept: " + insertion.added());
        require(insertion.renamed().equals(Map.of("gem", "gem_2", "coin", "coin_3")), "renames are listed: "
                + insertion.renamed());
        require(result.rewards.get(3).weight == 10 && result.rewards.get(5).id.equals("bonus"),
                "inserted rewards keep their odds and order");
        require(result.rewards.stream().map(r -> r.id).distinct().count() == 6, "every id is unique afterwards");
        require(insertion.notes().isEmpty() && !insertion.hasCommand(), "same mode, no command: nothing to note: "
                + insertion.notes());
        require(insertion.lines().get(0).contains("&f3 &7reward(s)") && insertion.lines().get(1).contains("gem → gem_2"),
                "the confirmation says what is added and renamed: " + insertion.lines());

        LootBoxDraft percent = pool("pct", "EXACT_PERCENT", reward("a", "60"));
        TemplateApply.Insertion mismatch = TemplateApply.rewardSet(set, percent);
        require(mismatch.notes().stream().anyMatch(n -> n.contains("saved from a WEIGHTED pool")
                && n.contains("percents")), "a mode mismatch says what to fix: " + mismatch.notes());
        require(mismatch.notes().stream().anyMatch(n -> n.contains("60% assigned")),
                "a percent pool shows where its sum stands: " + mismatch.notes());

        LootBoxDraft commands = pool("cmd", "WEIGHTED", weighted("run", 1));
        commands.rewards.get(0).type = "COMMAND";
        TemplateApply.Insertion withCommand = TemplateApply.rewardSet(
                TemplateLibrary.rewardSet("cmd_rewards", "Cmd", commands, "alice", 1L), target);
        require(withCommand.hasCommand() && withCommand.notes().stream().anyMatch(n -> n.contains("rewards.command")),
                "COMMAND rewards are flagged for the permission check");
    }

    private static void crateLookCopiesOnlyTheLook() {
        CrateInstance source = new CrateInstance();
        source.crateId = "spawn_crate";
        source.lootboxId = "example_supply";
        source.visuals.idleParticle = "Block_Treasure_Sparks";
        source.visuals.openParticle = "Block_Break_Treasure";
        source.visuals.openSound = "SFX_Chest_Legendary_Open";
        source.visuals.nameplate = true;
        LootBoxTemplate look = TemplateLibrary.crateLook("spawn_look", "Spawn look", source, "alice", 1L);
        require(TemplateLibrary.problems(look).isEmpty(), "a crate look has problems: " + TemplateLibrary.problems(look));
        require("crate spawn_crate".equals(look.source) && LootBoxTemplate.CRATE_LOOK.equals(look.kind),
                "a crate look doesn't say where it came from");
        require(TemplateLibrary.summary(look).equals("Crate look · idle Block_Treasure_Sparks · open "
                + "Block_Break_Treasure · sound SFX_Chest_Legendary_Open · floating name"),
                "the crate look's summary is wrong: " + TemplateLibrary.summary(look));
        source.visuals.idleParticle = "changed";
        require("Block_Treasure_Sparks".equals(look.crate.idleParticle), "the crate look shares the crate's visuals");

        CrateInstance target = new CrateInstance();
        target.crateId = "vault_crate";
        target.lootboxId = "mystic_treasure";
        target.x = 4;
        target.protection.preventDamage = true;
        CrateInstance after = TemplateApply.crateLook(look, target);
        require("mystic_treasure".equals(after.lootboxId) && after.x == 4 && after.protection.preventDamage
                && "vault_crate".equals(after.crateId), "applying a crate look changed more than the look");
        require("Block_Break_Treasure".equals(after.visuals.openParticle) && after.visuals.nameplate,
                "applying a crate look didn't copy the look");
        require(target.visuals.openParticle.isEmpty(), "applying a crate look changed the crate it was given");
        List<String> lines = TemplateApply.crateLookLines(target, after);
        require(lines.size() == 4 && lines.get(0).contains("none") && lines.get(3).contains("off")
                && lines.get(3).contains("on"), "the crate look's change lines are wrong: " + lines);
        require(TemplateApply.crateLookLines(after, TemplateApply.crateLook(look, after)).get(0)
                .contains("Nothing changes"), "a crate that already looks like this reports changes");

        LootBoxTemplate missing = TemplateLibrary.crateLook("bare_look", "Bare", target, "alice", 1L);
        missing.crate = null;
        require(TemplateLibrary.problems(missing).contains("the crate look is missing"), "an empty crate look passed");
        require("Crate look · no effects".equals(TemplateLibrary.summary(TemplateLibrary.crateLook("plain_look",
                "Plain", new CrateInstance(), "alice", 1L))), "a crate without effects doesn't say so");
        require(TemplateLibrary.suggestId("spawn_crate", LootBoxTemplate.CRATE_LOOK).equals("spawn_crate_look"),
                "a crate's id doesn't become a template id");
    }

    private static void templateRulesAndIds() {
        LootBoxTemplate look = TemplateLibrary.keyLook("ok_look", "Ok", key("mystic:a", "Ingredient_Bar_Gold", "&6A",
                List.of(), 1, 0), "alice", 1L);
        require(TemplateLibrary.problems(look).isEmpty(), "a complete look has no problems: "
                + TemplateLibrary.problems(look));
        LootBoxTemplate blankKey = TemplateLibrary.keyLook("blank_look", "Blank", key("mystic:a", "", "&6A",
                List.of(), 1, 0), "alice", 1L);
        require(TemplateLibrary.problems(blankKey).contains("the key look has no base item"), "a look needs a base item");
        LootBoxTemplate empty = TemplateLibrary.rewardSet("empty_set", "", pool("e", "WEIGHTED"), "alice", 1L);
        require(TemplateLibrary.problems(empty).containsAll(List.of("the template has no name",
                "the reward set has no rewards")), "a set needs a name and rewards: " + TemplateLibrary.problems(empty));
        LootBoxTemplate badId = TemplateLibrary.rewardSet("Bad-Id", "x", pool("p", "WEIGHTED", weighted("a", 1)),
                "alice", 1L);
        require(TemplateLibrary.problems(badId).get(0).contains("must match"), "a bad id is refused");

        require(TemplateLibrary.suggestId("mystic:royal_key", LootBoxTemplate.KEY_LOOK).equals("mystic_royal_key_look"),
                "a key's id becomes a template id");
        String longId = TemplateLibrary.suggestId("a".repeat(48), LootBoxTemplate.REWARD_SET);
        require(longId.length() == 48 && longId.endsWith("_rewards") && DraftValidator.LOOTBOX_ID.matcher(longId).matches(),
                "a suggested id always fits the pattern: " + longId);
        require(DraftValidator.LOOTBOX_ID.matcher(TemplateLibrary.suggestId("", LootBoxTemplate.KEY_LOOK)).matches(),
                "even from nothing");

        List<String> recorded = new ArrayList<>();
        DraftStore.Auditor renamed = TemplateLibrary.renamed((actor, action, target, detail) -> recorded.add(action));
        renamed.record("a", "DRAFT_SAVE", "t", "");
        renamed.record("a", "DRAFT_ARCHIVE", "t", "");
        renamed.record("a", "TEMPLATE_APPLY", "t", "");
        require(recorded.equals(List.of("TEMPLATE_SAVE", "TEMPLATE_ARCHIVE", "TEMPLATE_APPLY")),
                "the store's actions are renamed for the trail: " + recorded);
        require(AuditQuery.GROUPS.get("TEMPLATES").containsAll(List.of("TEMPLATE_SAVE", "TEMPLATE_ARCHIVE",
                "TEMPLATE_APPLY", "TEMPLATE_OVERWRITE")), "and the audit filter lists them");
    }

    private static void templateStoreIsOptimistic() throws IOException {
        Path root = Files.createTempDirectory("lootbox-templates");
        try {
            List<String> trail = new CopyOnWriteArrayList<>();
            TemplateLibrary library = new TemplateLibrary(root.resolve(TemplateLibrary.FOLDER),
                    (actor, action, target, detail) -> trail.add(action + " " + target), IO);
            DraftStore<LootBoxTemplate> store = library.store();
            LootBoxTemplate set = TemplateLibrary.rewardSet("royal_rewards", "Royal", pool("royal", "WEIGHTED",
                    weighted("gem", 1)), "alice", 1L);
            require(await(store.save(set, null, "alice")) instanceof DraftStore.Saved, "a new template saves");
            require(Files.exists(root.resolve("templates/royal_rewards.json")), "as templates/<id>.json");
            require(await(store.save(set, null, "bob")) instanceof DraftStore.Conflict,
                    "a second new template with the same id is refused, not overwritten");

            DraftStore.Loaded<LootBoxTemplate> loaded = await(store.load("royal_rewards")).orElseThrow();
            LootBoxTemplate renamed = DraftLifecycle.freeze(loaded.draft(), LootBoxTemplate.class);
            renamed.name = "Royal (v2)";
            require(await(store.save(renamed, loaded.hash(), "alice")) instanceof DraftStore.Saved,
                    "a rename with the loaded hash saves");
            renamed.name = "Royal (stale)";
            require(await(store.save(renamed, loaded.hash(), "bob")) instanceof DraftStore.Conflict,
                    "a rename with a stale hash is refused");
            require(await(store.load("royal_rewards")).orElseThrow().draft().name.equals("Royal (v2)"),
                    "and the file keeps the first rename");

            require(await(store.archive("royal_rewards", "alice")) instanceof DraftStore.Archived,
                    "delete moves the file");
            require(await(store.list()).drafts().isEmpty(), "out of the library");
            try (Stream<Path> archived = Files.list(root.resolve("templates/_archive"))) {
                require(archived.count() == 1, "into templates/_archive/");
            }
            require(trail.equals(List.of("TEMPLATE_SAVE royal_rewards", "TEMPLATE_SAVE royal_rewards",
                    "TEMPLATE_ARCHIVE royal_rewards")), "the trail names template actions: " + trail);
        } finally {
            delete(root);
        }
    }

    private static void applyingWritesWithTheLoadedHash() throws IOException {
        Path root = Files.createTempDirectory("lootbox-apply");
        try {
            DraftLifecycle<LootBoxDraft> lifecycle = new DraftLifecycle<>(draft -> List.of(), draft -> "x", id -> null,
                    id -> false, draft -> false);
            DraftStore<LootBoxDraft> pools = new DraftStore<>(DraftStore.POOLS, root, lifecycle,
                    (actor, action, target, detail) -> {
                    }, new DraftStore.Listener<>() {
                        @Override
                        public void changed(String id, LootBoxDraft draft) {
                        }

                        @Override
                        public void listed(DraftStore.Listing<LootBoxDraft> listing) {
                        }
                    }, IO);
            require(await(pools.save(pool("dest", "WEIGHTED", weighted("gem", 1)), null, "alice"))
                    instanceof DraftStore.Saved, "the target pool exists");
            LootBoxTemplate set = TemplateLibrary.rewardSet("s", "S", pool("src", "WEIGHTED", weighted("gem", 5)),
                    "alice", 1L);

            DraftStore.Loaded<LootBoxDraft> before = await(pools.load("dest")).orElseThrow();
            TemplateApply.Insertion insertion = TemplateApply.rewardSet(set, before.draft());
            // Someone saves the pool between the confirmation and Confirm.
            LootBoxDraft edited = DraftLifecycle.freeze(before.draft(), LootBoxDraft.class);
            edited.description = "edited elsewhere";
            require(await(pools.save(edited, before.hash(), "bob")) instanceof DraftStore.Saved, "the other save lands");
            require(await(pools.save(insertion.pool(), before.hash(), "alice")) instanceof DraftStore.Conflict,
                    "applying with the hash it loaded is refused once the file changed");
            LootBoxDraft onDisk = await(pools.load("dest")).orElseThrow().draft();
            require(onDisk.rewards.size() == 1 && "edited elsewhere".equals(onDisk.description),
                    "and the other save is kept, not overwritten");

            DraftStore.Loaded<LootBoxDraft> fresh = await(pools.load("dest")).orElseThrow();
            TemplateApply.Insertion again = TemplateApply.rewardSet(set, fresh.draft());
            require(await(pools.save(again.pool(), fresh.hash(), "alice")) instanceof DraftStore.Saved,
                    "applying again from the fresh copy saves");
            LootBoxDraft after = await(pools.load("dest")).orElseThrow().draft();
            require(after.rewards.size() == 2 && after.rewards.get(1).id.equals("gem_2")
                    && "edited elsewhere".equals(after.description), "with both changes: " + Json.toString(after));
        } finally {
            delete(root);
        }
    }

    // ----- Fixtures ---------------------------------------------------------------

    /** An in-memory audit namespace that answers asynchronously and records what was read. */
    private static final class InMemoryAudit implements AuditQuery.Source {
        final Map<String, JsonArray> buckets = new TreeMap<>();
        final List<String> keys = new ArrayList<>();
        final List<String> loaded = new CopyOnWriteArrayList<>();
        final AtomicInteger lists = new AtomicInteger();

        void add(String bucket, JsonObject entry) {
            if (!buckets.containsKey(bucket)) {
                keys.add(bucket);
            }
            buckets.computeIfAbsent(bucket, b -> new JsonArray()).add(entry);
        }

        @Override
        public CompletableFuture<List<String>> list() {
            lists.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> List.copyOf(keys), IO);
        }

        @Override
        public CompletableFuture<JsonElement> load(String bucket) {
            loaded.add(bucket);
            return CompletableFuture.supplyAsync(() -> buckets.containsKey(bucket) ? buckets.get(bucket).deepCopy()
                    : null, IO);
        }
    }

    private static List<AuditQuery.Record> all(InMemoryAudit audit, AuditQuery.Filter filter) {
        List<AuditQuery.Record> records = new ArrayList<>();
        await(AuditQuery.export(audit, filter, 1000, 1000, IO, records::add));
        return records;
    }

    private static JsonObject entry(long at, String server, String actor, String action, String target, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("at", at);
        entry.addProperty("server", server);
        entry.addProperty("actor", actor);
        entry.addProperty("action", action);
        entry.addProperty("target", target);
        entry.addProperty("detail", detail);
        return entry;
    }

    private static LootBoxRevision revision(long... weights) {
        LootBoxDraft draft = pool("sim", "WEIGHTED");
        LootBoxRevision revision = new LootBoxRevision();
        revision.lootboxId = "sim";
        revision.revision = 2;
        revision.contentHash = "hash";
        for (int i = 0; i < weights.length; i++) {
            draft.rewards.add(weighted("r" + i, weights[i]));
            revision.weights.add(weights[i]);
        }
        revision.spec = draft;
        return revision;
    }

    private static LootBoxDraft pool(String id, String mode, LootBoxDraft.RewardSpec... rewards) {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = id;
        draft.displayName = id;
        draft.mode = mode;
        draft.rewards.addAll(List.of(rewards));
        return draft;
    }

    private static LootBoxDraft.RewardSpec weighted(String id, long weight) {
        LootBoxDraft.RewardSpec reward = reward(id, null);
        reward.weight = weight;
        return reward;
    }

    private static LootBoxDraft.RewardSpec reward(String id, String percent) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.id = id;
        reward.name = id;
        reward.type = "ITEM";
        reward.percent = percent;
        reward.item = new LootBoxDraft.ItemSpec();
        reward.item.itemId = "Ingredient_Bar_Iron";
        return reward;
    }

    private static KeyDraft key(String id, String base, String name, List<String> lore, int uses, long expiry) {
        KeyDraft key = new KeyDraft();
        key.id = id;
        key.baseItemId = base;
        key.name = name;
        key.lore = new ArrayList<>(lore);
        key.uses = uses;
        key.expiresAfterDays = expiry;
        return key;
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("an async call failed: " + e, e.getCause() != null ? e.getCause() : e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
