package com.jellomakker.goldenapplecounter.ui;

import com.jellomakker.goldenapplecounter.GoldenAppleCounterClient;
import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class GoldenAppleCounterConfigScreen extends Screen {
    private final Screen parent;

    private final GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();

    private ButtonWidget enabledButton;
    private ButtonWidget normalAppleButton;
    private ButtonWidget enchantedAppleButton;
    private ButtonWidget showOnNameButton;
    private ButtonWidget includeSelfButton;
    private ButtonWidget showBackgroundButton;

    public GoldenAppleCounterConfigScreen(Screen parent) {
        super(Text.literal("Golden Apple Counter"));
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

        this.normalAppleButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.countNormalGoldenApple = !this.config.countNormalGoldenApple;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing, width, height).build();

        this.enchantedAppleButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.countEnchantedGoldenApple = !this.config.countEnchantedGoldenApple;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 2, width, height).build();

        this.showOnNameButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.showOnPlayerName = !this.config.showOnPlayerName;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 3, width, height).build();

        this.includeSelfButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.includeSelfDisplay = !this.config.includeSelfDisplay;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 4, width, height).build();

        this.showBackgroundButton = ButtonWidget.builder(Text.empty(), button -> {
            this.config.showBackground = !this.config.showBackground;
            this.updateLabels();
        }).dimensions(centerX - width / 2, y + spacing * 5, width, height).build();

        this.addDrawableChild(this.enabledButton);
        this.addDrawableChild(this.normalAppleButton);
        this.addDrawableChild(this.enchantedAppleButton);
        this.addDrawableChild(this.showOnNameButton);
        this.addDrawableChild(this.includeSelfButton);
        this.addDrawableChild(this.showBackgroundButton);

        // Reset counts button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset All Counts"), button -> {
            GoldenAppleCounterClient.clearAll();
        }).dimensions(centerX - width / 2, y + spacing * 7, width, height).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            this.config.save();
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).dimensions(centerX - width / 2, y + spacing * 8, width, height).build());

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
        this.normalAppleButton.setMessage(Text.literal("Count Golden Apples: " + onOff(this.config.countNormalGoldenApple)));
        this.enchantedAppleButton.setMessage(Text.literal("Count Enchanted Apples: " + onOff(this.config.countEnchantedGoldenApple)));
        this.showOnNameButton.setMessage(Text.literal("Show Counter On Name: " + onOff(this.config.showOnPlayerName)));
        this.includeSelfButton.setMessage(Text.literal("Show Counter For Self: " + onOff(this.config.includeSelfDisplay)));
        this.showBackgroundButton.setMessage(Text.literal("See Through Walls: " + onOff(this.config.showBackground)));
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
