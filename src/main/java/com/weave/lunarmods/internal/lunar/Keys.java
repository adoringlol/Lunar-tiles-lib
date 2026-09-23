package com.weave.lunarmods.internal.lunar;

/** Names of the resolved members; they double as the keys of the build cache. */
final class Keys {
    static final String MAIN_INSTANCE = "main.instance";
    static final String MAIN_FEATURES = "main.features";
    static final String FEATURE = "feature";
    static final String FEATURE_HOOK = "feature.optionsHook";
    static final String MANAGER_REGISTER = "manager.register";
    static final String MANAGER_ENABLE = "manager.enable";
    static final String MANAGER_REFRESH = "manager.refresh";
    static final String MANAGER_ITEMS = "manager.items";
    static final String BASE = "base";
    static final String BASE_CONSTRUCTOR = "base.constructor";
    static final String BASE_LOAD = "base.load";
    static final String BASE_SAVE = "base.save";
    static final String BASE_METADATA = "base.metadata";
    static final String BASE_DISPLAY = "base.display";
    static final String META_BUILDER = "meta.builder";
    static final String META_CATEGORIES = "meta.categories";
    static final String META_LIST_A = "meta.listA";
    static final String META_LIST_B = "meta.listB";
    static final String META_TEXT_A = "meta.textA";
    static final String META_TEXT_B = "meta.textB";
    static final String META_BUILD = "meta.build";
    static final String META_NAME = "meta.name";
    static final String CATEGORY_VALUES = "category.values";
    static final String DISPLAY_FACTORY = "display.factory";
    static final String RL_CREATE = "resourceLocation.create";
    static final String REGISTRAR_ADD = "registrar.add";
    static final String REGISTRAR_LIST = "registrar.list";
    static final String OPTION_GET = "option.get";
    static final String OPTION_SET = "option.set";
    static final String OPTION_RESET = "option.reset";
    static final String BUILDER_BUILD = "builder.build";
    static final String BUILDER_NAME = "builder.name";
    static final String BUILDER_HIDDEN = "builder.hidden";

    // HUD elements; all optional, present only when every one of them was found.
    static final String TRAIT_SET = "trait.set";
    static final String HUD_TRAIT = "hud.trait";
    static final String HUD_ANCHOR = "hud.anchor";
    static final String HUD_CUSTOM = "hud.custom";
    static final String HUD_TEXT_CONSTRUCTOR = "hud.text.constructor";
    static final String HUD_TEXT_SIZES = "hud.text.sizes";
    static final String HUD_TEXT_VALUE = "hud.text.value";
    static final String HUD_TEXT_WIDTH = "hud.text.width";
    static final String HUD_TEXT_HEIGHT = "hud.text.height";
    static final String HUD_SIZE = "hud.size";

    // Per-kind members, stored as "kind.<KIND>.<member>".
    static final String FACTORY = "factory";
    static final String DEFAULT = "default";
    static final String RANGE = "range";
    static final String CHOICES = "choices";
    static final String LABELS = "labels";
    static final String ACTION = "action";
    static final String HEADER = "header";
    static final String LABEL_NAME = "labelName";
    static final String KEY_DOWN = "keyDown";
    static final String PRESS = "press";
    static final String COMBO = "combo";
    static final String COMBO_KEY = "comboKey";

    private Keys() {
    }

    static String kind(Kind kind, String member) {
        return "kind." + kind.name() + "." + member;
    }
}
