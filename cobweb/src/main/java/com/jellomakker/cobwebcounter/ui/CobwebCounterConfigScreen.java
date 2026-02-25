package com.jellomakker.cobwebcounter.ui;

import com.jellomakker.cobwebcounter.CobwebCounterClient;
import com.jellomakker.cobwebcounter.config.CobwebCounterConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class CobwebCounterConfigScreen extends Screen {
    private final Screen parent;

    private final CobwebCounterConfig config = CobwebCounterConfig.get();

    private ButtonWidget enabledButton;
    private ButtonWidget showOnNameButton;
    private ButtonWidget includeSelfButton;
    private ButtonWidget showBackgroundButton;

    public CobwebCounterConfigScreen(Screen parent) {
        super(Text.literal("Cobweb Counter"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 4;
        int width = 260;
        int height = 20;
        int spacing = 24;

        this.enabledButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.enabled = !this.config.enabled;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y, width, height).build();

        this.showOnNameButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.showOnPlayerName = !this.config.showOnPlayerName;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing, width, height).build();

        this.includeSelfButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.includeSelfDisplay = !this.config.includeSelfDisplay;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 2, width, height).build();

        this.showBackgroundButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.showBackground = !this.config.showBackground;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 3, width, height).build();

        this.addDrawableChild(this.enabledButton);
        this.addDrawableChild(this.showOnNameButton);
        this.addDrawableChild(this.includeSelfButton);
        this.addDrawableChild(this.showBackgroundButton);

        // Reset counts button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset All Counts"), button -> {
            CobwebCounterClient.clearAll();
        }).dimensions(centerX - width / 2, y + spacing * 5, width, height).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            this.config.save();
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).dimensions(centerX - width / 2, y + spacing * 6, width, height).build());

        this.updateLabels();
    }

    @Override
    public void close() {
        this.config.save();
        assert this.client != null;
        this.client.setScreen(this.parent);
    }

    private void updateLabels() {
        this.enabledButton.setMessage(Text.literal("Enabled: " + onOff(this.config.enabled)));
        this.showOnNameButton.setMessage(Text.literal("Show Counter On Name: " + onOff(this.config.showOnPlayerName)));
        this.includeSelfButton.setMessage(Text.literal("Show Counter For Self: " + onOff(this.config.includeSelfDisplay)));
        this.showBackgroundButton.setMessage(Text.literal("See Through Walls: " + onOff(this.config.showBackground)));
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
