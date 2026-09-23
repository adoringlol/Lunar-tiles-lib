package net.weavemc.api;

import java.lang.instrument.Instrumentation;

/**
 * Compile-only copy of Weave's entry point interface; it is not packaged in the jar, Weave provides
 * the real one at runtime. Both methods are abstract here, as in Weave builds that compile them
 * without default bodies, so the entry point must implement both.
 */
public interface ModInitializer {
    void preInit(Instrumentation instrumentation);

    void init();
}
