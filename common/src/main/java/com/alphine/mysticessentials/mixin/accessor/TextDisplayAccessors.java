package com.alphine.mysticessentials.mixin.accessor;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessors {

    @Accessor("DATA_TEXT_ID")
    static EntityDataAccessor<Component> me$text() { throw new AssertionError(); }

    @Accessor("DATA_BACKGROUND_COLOR_ID")
    static EntityDataAccessor<Integer> me$background() { throw new AssertionError(); }

    @Accessor("DATA_TEXT_OPACITY_ID")
    static EntityDataAccessor<Byte> me$textOpacity() { throw new AssertionError(); }

    @Accessor("DATA_STYLE_FLAGS_ID")
    static EntityDataAccessor<Byte> me$flags() { throw new AssertionError(); }

    @Accessor("DATA_LINE_WIDTH_ID")
    static EntityDataAccessor<Integer> me$lineWidth() { throw new AssertionError(); }
}
