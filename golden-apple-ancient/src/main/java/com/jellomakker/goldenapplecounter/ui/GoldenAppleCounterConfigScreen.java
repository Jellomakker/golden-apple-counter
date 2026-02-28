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
        int w = 260, h = 20, s = 24;

        this.enabledButton = ButtonWidget.builder(Text.empty(), b -> { config.enabled = !config.enabled; updateLabels(); }).dimensions(centerX - w/2, y, w, h).build();
        this.normalAppleButton = ButtonWidget.builder(Text.empty(), b -> { config.countNormalGoldenApple = !config.countNormalGoldenApple; updateLabels(); }).dimensions(centerX - w/2, y+s, w, h).build();
        this.enchantedAppleButton = ButtonWidget.builder(Text.empty(), b -> { config.countEnchantedGoldenApple = !config.countEnchantedGoldenApple; updateLabels(); }).dimensions(centerX - w/2, y+s*2, w, h).build();
        this.showOnNameButton = ButtonWidget.builder(Text.empty(), b -> { config.showOnPlayerName = !config.showOnPlayerName; updateLabels(); }).dimensions(centerX - w/2, y+s*3, w, h).build();
        this.includeSelfButton = ButtonWidget.builder(Text.empty(), b -> { config.includeSelfDisplay = !config.includeSelfDisplay; updateLabels(); }).dimensions(centerX - w/2, y+s*4, w, h).build();
        this.showBackgroundButton = ButtonWidget.builder(Text.empty(), b -> { config.showBackground = !config.showBackground; updateLabels(); }).dimensions(centerX - w/2, y+s*5, w, h).build();

        addDrawableChild(enabledButton);
        addDrawableChild(normalAppleButton);
        addDrawableChild(enchantedAppleButton);
        addDrawableChild(showOnNameButton);
        addDrawableChild(includeSelfButton);
        addDrawableChild(showBackgroundButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Reset All Counts"), b -> GoldenAppleCounterClient.clearAll()).dimensions(centerX - w/2, y+s*7, w, h).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> { config.save(); assert client != null; client.setScreen(parent); }).dimensions(centerX - w/2, y+s*8, w, h).build());

        updateLabels();
    }

    @Override
    public void close() {
        config.save();
        assert client != null;
        client.setScreen(parent);
    }

    private void updateLabels() {
        enabledButton.setMessage(Text.literal("Enabled: " + onOff(config.enabled)));
        normalAppleButton.setMessage(Text.literal("Count Golden Apples: " + onOff(config.countNormalGoldenApple)));
        enchantedAppleButton.setMessage(Text.literal("Count Enchanted Apples: " + onOff(config.countEnchantedGoldenApple)));
        showOnNameButton.setMessage(Text.literal("Show Counter On Name: " + onOff(config.showOnPlayerName)));
        includeSelfButton.setMessage(Text.literal("Show Counter For Self: " + onOff(config.includeSelfDisplay)));
        showBackgroundButton.setMessage(Text.literal("See Through Walls: " + onOff(config.showBackground)));
    }

    private static String onOff(boolean v) { return v ? "ON" : "OFF"; }
}
