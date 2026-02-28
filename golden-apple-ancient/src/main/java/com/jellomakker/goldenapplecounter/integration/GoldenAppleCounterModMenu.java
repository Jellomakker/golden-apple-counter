package com.jellomakker.goldenapplecounter.integration;

import com.jellomakker.goldenapplecounter.ui.GoldenAppleCounterConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class GoldenAppleCounterModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GoldenAppleCounterConfigScreen::new;
    }
}
