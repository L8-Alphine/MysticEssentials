package com.alphine.mysticessentials.mixin.accessor;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.BillboardConstraints.class)
public interface BillboardConstraintsAccessor {
    @Accessor("id")
    byte me$id();
}

