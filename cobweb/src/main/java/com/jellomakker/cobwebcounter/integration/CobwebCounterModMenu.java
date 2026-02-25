package com.jellomakker.cobwebcounter.integration;

import com.jellomakker.cobwebcounter.ui.CobwebCounterConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class CobwebCounterModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CobwebCounterConfigScreen::new;
    }
}
