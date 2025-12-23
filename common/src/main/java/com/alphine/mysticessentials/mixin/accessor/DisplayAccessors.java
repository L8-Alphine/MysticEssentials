package com.alphine.mysticessentials.mixin.accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.class)
public interface DisplayAccessors {

    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3f> me$translation() { throw new AssertionError(); }

    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3f> me$scale() { throw new AssertionError(); }

    @Accessor("DATA_LEFT_ROTATION_ID")
    static EntityDataAccessor<Quaternionf> me$leftRot() { throw new AssertionError(); }

    @Accessor("DATA_RIGHT_ROTATION_ID")
    static EntityDataAccessor<Quaternionf> me$rightRot() { throw new AssertionError(); }

    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> me$billboard() { throw new AssertionError(); }

    @Accessor("DATA_GLOW_COLOR_OVERRIDE_ID")
    static EntityDataAccessor<Integer> me$glowColor() { throw new AssertionError(); }
}
