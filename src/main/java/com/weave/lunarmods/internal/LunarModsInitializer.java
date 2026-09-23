package com.weave.lunarmods.internal;

import com.weave.lunarmods.internal.lunar.LunarRuntime;
import net.weavemc.api.ModInitializer;

import java.lang.instrument.Instrumentation;

/**
 * Weave entry point. Only {@link ModInitializer} is used from Weave, and both of its methods are
 * implemented: some Weave builds compile them as abstract, with no default bodies.
 */
public final class LunarModsInitializer implements ModInitializer {
    @Override
    public void preInit(Instrumentation instrumentation) {
        LunarRuntime.install(instrumentation);
    }

    @Override
    public void init() {
        Ticker.start();
    }
}
