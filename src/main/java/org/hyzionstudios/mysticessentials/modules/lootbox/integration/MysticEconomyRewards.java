package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;

/**
 * ECONOMY rewards through MysticEconomy (P3-WP7), provider id
 * {@code mysticeconomy}: a typed deposit from the server account into the
 * player's account, in the reward's currency (or MysticEconomy's default).
 *
 * <p><b>Reconcilable, within limits.</b> Every deposit carries the fulfillment id
 * in its ledger metadata ({@code lootboxFulfillment}). After a crash between
 * "attempted" and "delivered", the player's MysticEconomy ledger entries for
 * module {@code mysticessentials} are searched for it: found and completed
 * settles the action, found and failed lets it run again, and not found sends it
 * to staff review, because the search is bounded (MysticEconomy keeps a recent
 * window and has no lookup by reference) and can't prove the deposit never
 * happened. An exactly-once deposit needs MysticEconomy to accept an external
 * reference and look it up; see {@code docs/lootbox/implementation-plan.md}, P3
 * status.</p>
 *
 * <p>Bound by reflection to
 * {@code org.hyzionstudios.mysticeconomy.api.MysticEconomyProvider} and its
 * {@code MysticEconomyAPI}; registered only when those classes are present.</p>
 */
final class MysticEconomyRewards implements RewardProvider {

    static final String ID = "mysticeconomy";
    static final String MODULE = "mysticessentials";
    static final String REASON = "LOOTBOX_REWARD";
    static final String REFERENCE = "lootboxFulfillment";
    /** MysticEconomy's refusals that certainly moved no money. */
    private static final Set<String> CERTAIN_REFUSALS = Set.of("INSUFFICIENT_FUNDS", "ACCOUNT_FROZEN",
            "ACCOUNT_NOT_FOUND", "INVALID_AMOUNT", "SAME_ACCOUNT");

    private record Hooks(Method isAvailable, Method get, Method payments, Method accounts, Method ledger,
            Method defaultCurrency, Method format, Method transfer, Method getOrCreatePlayer, Method historyByModule,
            Method playerAccount, Object serverAccount, Method resultSuccess, Method resultError,
            Method resultTransaction, Method txId, Method txMetadata, Method txStatus) {
    }

    private final Hooks hooks;
    private final Function<UUID, String> names;
    private final IntSupplier reconcileDepth;

    private MysticEconomyRewards(Hooks hooks, Function<UUID, String> names, IntSupplier reconcileDepth) {
        this.hooks = hooks;
        this.names = names;
        this.reconcileDepth = reconcileDepth;
    }

    /** The provider, or {@code null} when MysticEconomy's API classes are not present. */
    static MysticEconomyRewards detect(Function<UUID, String> names, IntSupplier reconcileDepth) {
        try {
            Class<?> provider = Reflect.require("org.hyzionstudios.mysticeconomy.api.MysticEconomyProvider");
            Class<?> api = Reflect.require("org.hyzionstudios.mysticeconomy.api.MysticEconomyAPI");
            Class<?> accountId = Reflect.require("org.hyzionstudios.mysticeconomy.api.model.AccountId");
            Class<?> result = Reflect.require("org.hyzionstudios.mysticeconomy.api.model.TransactionResult");
            Class<?> transaction = Reflect.require("org.hyzionstudios.mysticeconomy.api.model.Transaction");
            Method payments = Reflect.method(api, "payments");
            Method accounts = Reflect.method(api, "accounts");
            Method ledger = Reflect.method(api, "ledger");
            Method transfer = Reflect.method(payments.getReturnType(), "transfer", accountId, accountId,
                    BigDecimal.class, String.class, String.class, String.class, String.class, Map.class);
            Method getOrCreate = Reflect.method(accounts.getReturnType(), "getOrCreatePlayer", UUID.class,
                    String.class);
            Method history = Reflect.method(ledger.getReturnType(), "historyByModule", accountId, String.class,
                    int.class);
            Hooks hooks = new Hooks(Reflect.method(provider, "isAvailable"), Reflect.method(provider, "get"),
                    payments, accounts, ledger, Reflect.method(api, "defaultCurrency"),
                    Reflect.method(api, "format", BigDecimal.class, String.class), transfer, getOrCreate, history,
                    Reflect.method(accountId, "player", UUID.class), accountId.getField("SERVER_GLOBAL").get(null),
                    Reflect.method(result, "success"), Reflect.method(result, "error"),
                    Reflect.method(result, "transaction"), Reflect.method(transaction, "transactionId"),
                    Reflect.method(transaction, "metadata"), Reflect.method(transaction, "status"));
            return new MysticEconomyRewards(hooks, names, reconcileDepth);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String kind() {
        return "ECONOMY";
    }

    @Override
    public Guarantee guarantee() {
        return Guarantee.RECONCILABLE;
    }

    @Override
    public List<String> validate(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        BigDecimal amount = amount(params);
        if (amount == null || amount.signum() <= 0) {
            problems.add("amount must be a positive number");
        }
        String currency = params.get("currency");
        if (currency != null && !currency.isBlank() && !currency.trim().matches("[A-Za-z0-9_-]{1,32}")) {
            problems.add("currency must be a MysticEconomy currency id");
        }
        return problems;
    }

    @Override
    public String describe(Map<String, String> params) {
        BigDecimal amount = amount(params);
        if (amount == null) {
            return "Currency";
        }
        try {
            Object api = api();
            if (api != null) {
                return "Currency: " + Reflect.call(hooks.format(), api, amount, currency(api, params));
            }
        } catch (RuntimeException ignored) {
            // Falls back to the plain amount.
        }
        String currency = params.get("currency");
        return "Currency: " + amount.toPlainString() + (currency == null || currency.isBlank() ? "" : " " + currency);
    }

    @Override
    public CompletableFuture<Result> grant(String fulfillmentId, UUID player, Map<String, String> params) {
        Object api;
        try {
            api = api();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Result.failed("MysticEconomy is not running"));
        }
        if (api == null) {
            return CompletableFuture.completedFuture(Result.failed("MysticEconomy is not running"));
        }
        BigDecimal amount = amount(params);
        if (amount == null || amount.signum() <= 0) {
            return CompletableFuture.completedFuture(Result.failed("bad amount " + params.get("amount")));
        }
        try {
            Object accounts = Reflect.call(hooks.accounts(), api);
            String name = names.apply(player);
            // Player accounts are not created by a transfer; it would refuse with ACCOUNT_NOT_FOUND.
            Reflect.call(hooks.getOrCreatePlayer(), accounts, player, name == null ? player.toString() : name);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(REFERENCE, fulfillmentId);
            int hash = fulfillmentId.indexOf('#');
            metadata.put("lootboxOpening", hash > 0 ? fulfillmentId.substring(0, hash) : fulfillmentId);
            Object payments = Reflect.call(hooks.payments(), api);
            Object target = Reflect.call(hooks.playerAccount(), null, player);
            @SuppressWarnings("unchecked")
            CompletionStage<Object> transfer = (CompletionStage<Object>) Reflect.call(hooks.transfer(), payments,
                    hooks.serverAccount(), target, amount, currency(api, params), REASON, MODULE, "console",
                    metadata);
            return transfer.toCompletableFuture().handle((result, error) -> {
                if (error != null || result == null) {
                    return Result.unknown("transfer did not answer: " + error);
                }
                if (Boolean.TRUE.equals(Reflect.call(hooks.resultSuccess(), result))) {
                    Object tx = Reflect.call(hooks.resultTransaction(), result);
                    return Result.delivered("transaction " + (tx == null ? "?" : Reflect.call(hooks.txId(), tx)));
                }
                Object code = Reflect.call(hooks.resultError(), result);
                return code != null && CERTAIN_REFUSALS.contains(code.toString())
                        ? Result.failed("refused: " + code) : Result.unknown("transfer failed: " + code);
            });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Result.unknown("transfer threw " + e));
        }
    }

    @Override
    public CompletableFuture<Result> reconcile(String fulfillmentId, UUID player, Map<String, String> params) {
        Object api;
        try {
            api = api();
        } catch (RuntimeException e) {
            api = null;
        }
        if (api == null) {
            return CompletableFuture.completedFuture(Result.unknown("MysticEconomy is not running to ask"));
        }
        try {
            Object ledger = Reflect.call(hooks.ledger(), api);
            Object account = Reflect.call(hooks.playerAccount(), null, player);
            @SuppressWarnings("unchecked")
            CompletionStage<List<?>> history = (CompletionStage<List<?>>) Reflect.call(hooks.historyByModule(),
                    ledger, account, MODULE, Math.max(1, reconcileDepth.getAsInt()));
            return history.toCompletableFuture().handle((entries, error) -> {
                if (error != null || entries == null) {
                    return Result.unknown("ledger did not answer: " + error);
                }
                boolean refused = false;
                for (Object tx : entries) {
                    Object metadata = Reflect.call(hooks.txMetadata(), tx);
                    if (!(metadata instanceof Map<?, ?> map) || !fulfillmentId.equals(map.get(REFERENCE))) {
                        continue;
                    }
                    String status = String.valueOf(Reflect.call(hooks.txStatus(), tx));
                    if ("COMPLETED".equals(status)) {
                        return new Result(Outcome.ALREADY_DELIVERED, "ledger has transaction "
                                + Reflect.call(hooks.txId(), tx));
                    }
                    refused |= "FAILED".equals(status);
                }
                return refused ? Result.failed("the ledger shows the deposit was refused")
                        : Result.unknown("not among the last " + reconcileDepth.getAsInt() + " ledger entries");
            });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Result.unknown("ledger lookup threw " + e));
        }
    }

    private Object api() {
        if (!Boolean.TRUE.equals(Reflect.call(hooks.isAvailable(), null))) {
            return null;
        }
        return Reflect.call(hooks.get(), null);
    }

    private String currency(Object api, Map<String, String> params) {
        String currency = params.get("currency");
        return currency != null && !currency.isBlank() ? currency.trim()
                : String.valueOf(Reflect.call(hooks.defaultCurrency(), api));
    }

    private static BigDecimal amount(Map<String, String> params) {
        try {
            String raw = params.get("amount");
            return raw == null ? null : new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
