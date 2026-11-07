//#if MC>=11400
package com.replaymod.core.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.replaymod.core.events.PostRenderWorldCallback;
import com.replaymod.core.events.PreRenderHandCallback;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Inject(
            method = "renderWorld",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderHand:Z"
            ),
            require = 1
    )
    private void postRenderWorld(RenderTickCounter counter, CallbackInfo ci,
                                 @Local(ordinal = 1) Matrix4f viewMatrix) {
        // The other Matrix4f is the projection; the local bob/hurt stack is not the view.
        // Keep the callback's existing contract, including its normal matrix.
        MatrixStack matrixStack = new MatrixStack();
        matrixStack.multiplyPositionMatrix(viewMatrix);
        PostRenderWorldCallback.EVENT.invoker().postRenderWorld(matrixStack);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true, require = 1)
    private void preRenderHand(Camera camera, float partialTicks, Matrix4f viewMatrix, CallbackInfo ci) {
        if (PreRenderHandCallback.EVENT.invoker().preRenderHand()) {
            ci.cancel();
        }
    }
}
//#endif
