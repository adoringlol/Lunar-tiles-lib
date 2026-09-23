package com.weave.lunarmods.internal.lunar;

/** Lunar is running but has not created its mod manager yet; worth retrying a little later. */
public final class LunarNotReadyException extends IllegalStateException {
    LunarNotReadyException(String message) {
        super(message);
    }
}
