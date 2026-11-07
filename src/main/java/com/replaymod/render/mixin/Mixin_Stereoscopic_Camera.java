package com.replaymod.render.mixin;

import com.replaymod.render.capturer.StereoscopicOpenGlFrameCapturer;
import com.replaymod.render.hooks.EntityRendererHandler;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class Mixin_Stereoscopic_Camera implements EntityRendererHandler.IEntityRenderer {
    @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void replayModRender_setupStereoscopicProjection(CallbackInfoReturnable<Matrix4f> ci) {
        if (replayModRender_getHandler() != null) {
            Matrix4f offset;
            if (replayModRender_getHandler().data == StereoscopicOpenGlFrameCapturer.Data.LEFT_EYE) {
                offset = new Matrix4f().translation(0.07f, 0, 0);
            } else if (replayModRender_getHandler().data == StereoscopicOpenGlFrameCapturer.Data.RIGHT_EYE) {
                offset = new Matrix4f().translation(-0.07f, 0, 0);
            } else {
                return;
            }
            offset.mul(ci.getReturnValue());
            ci.setReturnValue(offset);
        }
    }

    // Modify the shared view before it reaches the frustum, world and hand renderers.
    @ModifyVariable(method = "renderWorld", at = @At("STORE"), index = 14, require = 1)
    private Matrix4f replayModRender_setupStereoscopicView(Matrix4f view) {
        EntityRendererHandler handler = replayModRender_getHandler();
        if (handler != null) {
            if (handler.data == StereoscopicOpenGlFrameCapturer.Data.LEFT_EYE) {
                return new Matrix4f().translation(0.1f, 0, 0).mul(view);
            } else if (handler.data == StereoscopicOpenGlFrameCapturer.Data.RIGHT_EYE) {
                return new Matrix4f().translation(-0.1f, 0, 0).mul(view);
            }
        }
        return view;
    }
}
