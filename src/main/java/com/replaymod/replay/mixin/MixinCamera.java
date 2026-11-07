package com.replaymod.replay.mixin;

import com.replaymod.replay.camera.CameraEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public class MixinCamera {
    @Shadow @Final private MinecraftClient client;

    @ModifyArg(
            method = "renderWorld",
            at = @At(value = "INVOKE",
                    target = "Lorg/joml/Matrix4f;rotation(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;",
                    remap = false),
            index = 0,
            require = 1
    )
    private Quaternionfc applyRoll(Quaternionfc rotation) {
        Entity entity = this.client.getCameraEntity() == null ? this.client.player : this.client.getCameraEntity();
        if (entity instanceof CameraEntity camera) {
            // The argument is already the inverse camera rotation. Left-multiply roll without
            // mutating either it or Camera's quaternion, before frustum/world/hand use the view.
            return new Quaternionf().rotationZ((float) Math.toRadians(camera.roll)).mul(rotation);
        }
        return rotation;
    }
}
