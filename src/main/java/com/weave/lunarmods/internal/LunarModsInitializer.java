package com.weave.lunarmods.internal;

import net.weavemc.api.ModInitializer;
import net.weavemc.api.event.EventBus;
import net.weavemc.api.event.ShutdownEvent;
import net.weavemc.api.event.TickEvent;

import java.util.function.Consumer;

/** Weave entry point: drives tile registration and saving from the client tick. */
public final class LunarModsInitializer implements ModInitializer {
    @Override
    public void init() {
        EventBus.subscribe(TickEvent.Post.class, (Consumer<TickEvent.Post>) event -> Registry.tick());
        EventBus.subscribe(ShutdownEvent.class, (Consumer<ShutdownEvent>) event -> Registry.shutdown());
    }
}
