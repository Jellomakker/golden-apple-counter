package com.jellomakker.potcounter.integration;

import com.jellomakker.potcounter.ui.PotCounterConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class PotCounterModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PotCounterConfigScreen::new;
    }
}
