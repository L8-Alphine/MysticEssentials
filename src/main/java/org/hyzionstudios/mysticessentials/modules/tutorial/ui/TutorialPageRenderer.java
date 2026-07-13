package org.hyzionstudios.mysticessentials.modules.tutorial.ui;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialEvents;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialModule;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialButtonDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialPageDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.util.TutorialPlaceholders;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Renders one {@link TutorialPageDefinition} on the shared
 * {@code MysticEssentials/TutorialPage.ui} layout: text rows appended into
 * {@code #ContentList}, buttons into {@code #ButtonList} (verified
 * template-row pattern). The page is always dismissable — a broken page must
 * never trap a player.
 */
public final class TutorialPageRenderer extends MysticPage {

    static final String PAGE_UI = "MysticEssentials/TutorialPage.ui";
    static final String TEXT_ROW_UI = "MysticEssentials/TutorialTextRow.ui";
    static final String BUTTON_ROW_UI = "MysticEssentials/TutorialButtonRow.ui";

    private final TutorialModule module;
    private final TutorialPageDefinition page;
    /** Tutorial the page was opened from (blank for {@code /tutorial page}). */
    private final String tutorialContext;
    private final List<TutorialButtonDefinition> renderedButtons = new ArrayList<>();

    public TutorialPageRenderer(MysticCore core, TutorialModule module, PlayerRef player,
            TutorialPageDefinition page, String tutorialContext) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.page = page;
        this.tutorialContext = tutorialContext == null ? "" : tutorialContext;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(PAGE_UI);
        cmd.set("#PageTitle.Text", resolve(page.title));
        String subtitle = resolve(page.subtitle);
        cmd.set("#PageSubtitle.Text", subtitle);
        cmd.set("#PageSubtitle.Visible", !subtitle.isBlank());

        int contentIndex = 0;
        if (page.content != null) {
            for (TutorialPageDefinition.ContentItem item : page.content) {
                if (item == null || item.type == null || !item.type.equalsIgnoreCase("text")) {
                    continue; // Only text content in the MVP; unknown types skipped.
                }
                cmd.append("#ContentList", TEXT_ROW_UI);
                cmd.set("#ContentList[" + contentIndex + "] #Text.Text", resolve(item.text));
                contentIndex++;
            }
        }

        renderedButtons.clear();
        if (page.buttons != null) {
            for (TutorialButtonDefinition button : page.buttons) {
                if (button == null || !button.isValid()) {
                    continue;
                }
                if (!module.config().ui.closeButtonEnabled
                        && TutorialButtonActionType.fromString(button.action.type)
                                == TutorialButtonActionType.CLOSE) {
                    continue;
                }
                int index = renderedButtons.size();
                renderedButtons.add(button);
                String row = "#ButtonList[" + index + "]";
                cmd.append("#ButtonList", BUTTON_ROW_UI);
                cmd.set(row + " #ButtonLabel.Text", resolve(button.text));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("button", button.id));
            }
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String buttonId = field(payload, "button");
        if (buttonId.isBlank()) {
            return;
        }
        TutorialButtonDefinition button = renderedButtons.stream()
                .filter(b -> buttonId.equals(b.id))
                .findFirst()
                .orElse(null);
        if (button == null) {
            return;
        }
        core.getEventBus().publish(new TutorialEvents.PageButtonClick(player.getUuid(),
                player.getUsername(), tutorialContext, page.id, button.id, button.action.type));
        module.actionHandler().execute(player, page.id, tutorialContext, button,
                new TutorialButtonActionHandler.PageControls() {
                    @Override
                    public void closePage() {
                        close(ref, store);
                        core.getEventBus().publish(new TutorialEvents.PageClose(player.getUuid(),
                                player.getUsername(), tutorialContext, page.id));
                    }

                    @Override
                    public void openPage(String pageId) {
                        TutorialPageDefinition next = module.loader().page(pageId).orElse(null);
                        if (next == null) {
                            module.logger().error("Page '" + page.id + "' links to unknown page '"
                                    + pageId + "'");
                            return;
                        }
                        reopen(ref, store, new TutorialPageRenderer(core, module, player, next,
                                tutorialContext));
                        core.getEventBus().publish(new TutorialEvents.PageOpen(player.getUuid(),
                                player.getUsername(), tutorialContext, next.id));
                    }
                });
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        core.getEventBus().publish(new TutorialEvents.PageClose(player.getUuid(),
                player.getUsername(), tutorialContext, page.id));
    }

    /** Placeholder pass + strip of colour markup (Label text renders plain). */
    private String resolve(String text) {
        String resolved = TutorialPlaceholders.apply(text, player, tutorialContext, module.loader());
        return resolved.replaceAll("(?i)&[0-9a-fk-or]", "").replaceAll("(?i)&#[0-9a-f]{6}", "");
    }
}
