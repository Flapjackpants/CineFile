package com.cinefile.mod;

import com.cinefile.core.CineFileConfig;
import com.cinefile.core.CineFileConfig.GapFill;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public class SettingsScreen extends Screen {
    private static final int WIDGET_WIDTH = 150;
    private static final List<String> STYLES =
        List.of("auto", "locked-dolly", "hero-low", "high-crane", "side-track", "orbit-reveal");

    private final Screen parent;
    private final CineFileConfig config = CineFileClient.config();
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private SettingsList list;

    public SettingsScreen(Screen parent) {
        super(Component.literal("CineFile"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(this.title, this.font);
        this.list = this.layout.addToContents(
            new SettingsList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(200).build());
        this.fill(this.list);
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        this.list.updateSize(this.width, this.layout);
    }

    private void fill(SettingsList l) {
        CineFileConfig c = this.config;
        l.header("AI");
        EditBox key = l.text("API key", c.apiKey, 512, v -> c.apiKey = v);
        key.addFormatter((s, offset) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
        key.setHint(Component.literal("Blank = no AI"));
        l.text("Base URL", c.baseUrl, 256, v -> c.baseUrl = v);
        l.text("Model", c.model, 128, v -> c.model = v);
        l.number("Max spend (USD)", () -> c.maxAiUsd, v -> c.maxAiUsd = v);
        l.toggle("Think harder", c.think, v -> c.think = v, null);
        l.toggle("Screenshot review", c.visualReview, v -> c.visualReview = v,
            "Sends screenshots of your world to the API provider");

        l.header("Shots");
        l.text("Project description", c.project, 1000, v -> c.project = v);
        l.number("Clip length (s)", () -> c.clipLengthS, v -> c.clipLengthS = v);
        l.number("Total duration (s)", () -> c.durationS, v -> c.durationS = v);
        l.add("Style", CycleButton.builder((String s) -> Component.literal(s), c.styleId).withValues(STYLES)
            .displayOnlyValue().create(0, 0, WIDGET_WIDTH, 20, Component.literal("Style"), (b, v) -> c.styleId = v));
        l.number("Tries per clip", () -> c.tries, v -> c.tries = (int) Math.round(v));

        l.header("Clip checks");
        l.toggle("Reject cuts", c.rejectCuts, v -> c.rejectCuts = v, null);
        l.toggle("Reject dimension changes", c.rejectDimensionChanges, v -> c.rejectDimensionChanges = v, null);
        l.toggle("Reject camera through blocks", c.rejectBlockClipping, v -> c.rejectBlockClipping = v, null);
        l.toggle("Reject blocked view", c.rejectBlockedView, v -> c.rejectBlockedView = v, null);
        l.number("Teleport distance (blocks)", () -> c.teleportBlocks, v -> c.teleportBlocks = v);
        l.number("Check every N ticks", () -> c.checkEveryTicks, v -> c.checkEveryTicks = (int) Math.round(v));

        l.header("Gaps");
        l.add("Fill gaps with", CycleButton.builder((GapFill g) -> Component.literal(switch (g) {
            case CUT -> "Cut";
            case TIMELAPSE -> "Timelapse";
            case NONE -> "Nothing";
        }), c.gapFill).withValues(GapFill.values()).displayOnlyValue()
            .create(0, 0, WIDGET_WIDTH, 20, Component.literal("Fill gaps with"), (b, v) -> c.gapFill = v));
        AbstractWidget cut = l.number("Cut length in ticks", () -> c.cutLengthTicks, v -> c.cutLengthTicks = (int) Math.round(v));
        cut.setTooltip(Tooltip.create(Component.literal(
            "0 removes the frames entirely. 1 or more plays a timelapse of that length, which shows frames from the middle of the skipped range")));
    }

    @Override
    public void onClose() {
        try {
            this.config.save(CineFileClient.configPath());
        } catch (IOException e) {
            // never log the config itself: it holds the API key
            CineFileClient.LOGGER.warn("Unable to save CineFile settings: {}", e.toString());
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    private class SettingsList extends ContainerObjectSelectionList<Row> {
        SettingsList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 25);
        }

        @Override
        public int getRowWidth() {
            return 310;
        }

        void header(String text) {
            this.addEntry(new Row(Component.literal(text).withStyle(s -> s.withBold(true).withColor(0xFFFF55)), null));
        }

        <W extends AbstractWidget> W add(String label, W widget) {
            this.addEntry(new Row(Component.literal(label), widget));
            return widget;
        }

        EditBox text(String label, String value, int max, Consumer<String> set) {
            EditBox box = new EditBox(SettingsScreen.this.font, 0, 0, WIDGET_WIDTH, 20, Component.literal(label));
            box.setMaxLength(max);
            box.setValue(value);
            box.setResponder(set);
            return this.add(label, box);
        }

        EditBox number(String label, DoubleSupplier current, java.util.function.DoubleConsumer set) {
            double start = current.getAsDouble();
            EditBox box = new EditBox(SettingsScreen.this.font, 0, 0, WIDGET_WIDTH, 20, Component.literal(label));
            box.setMaxLength(12);
            box.setValue(start == Math.rint(start) ? Long.toString((long) start) : Double.toString(start));
            box.setResponder(s -> {
                String digits = s.replaceAll("[^0-9.]", "");
                if (!digits.equals(s)) {
                    box.setValue(digits); // re-enters this responder with clean text
                    return;
                }
                try {
                    set.accept(Double.parseDouble(s));
                } catch (NumberFormatException ignored) {
                    // keep the old value
                }
            });
            return this.add(label, box);
        }

        void toggle(String label, boolean value, Consumer<Boolean> set, String tooltip) {
            CycleButton<Boolean> b = CycleButton.onOffBuilder(value).displayOnlyValue()
                .create(0, 0, WIDGET_WIDTH, 20, Component.literal(label), (btn, v) -> set.accept(v));
            if (tooltip != null) {
                b.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            this.add(label, b);
        }
    }

    private class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Component label;
        private final AbstractWidget widget;

        Row(Component label, AbstractWidget widget) {
            this.label = label;
            this.widget = widget;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
            g.text(SettingsScreen.this.font, this.label, this.getContentX(), this.getContentYMiddle() - 4, 0xFFFFFFFF);
            if (this.widget != null) {
                this.widget.setX(this.getContentRight() - WIDGET_WIDTH);
                this.widget.setY(this.getContentY());
                this.widget.extractRenderState(g, mouseX, mouseY, a);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widget == null ? List.of() : List.of(this.widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widget == null ? List.of() : List.of(this.widget);
        }
    }
}
