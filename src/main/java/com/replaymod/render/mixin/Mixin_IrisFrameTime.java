package com.replaymod.render.mixin;

import com.replaymod.core.versions.MCVer;
import com.replaymod.render.hooks.EntityRendererHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalLong;

/** Keeps Iris frameTime/frameTimeCounter on the capture clock without replacing Iris's arithmetic. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class Mixin_IrisFrameTime {
    @Shadow private OptionalLong lastStartTime;

    @Unique private EntityRendererHandler replayModRender_clockOwner;
    @Unique private int replayModRender_logicalFrame;
    @Unique private long replayModRender_lastCaptureNs;

    @Inject(method = "beginFrame(J)V", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void replayModRender_useCaptureClock(long realNs, CallbackInfo ci) {
        EntityRendererHandler handler = ((EntityRendererHandler.IEntityRenderer) MCVer.getMinecraft().gameRenderer)
                .replayModRender_getHandler();
        if (handler == null || !handler.isCaptureActive()) {
            if (replayModRender_clockOwner != null) {
                // First ordinary frame after capture: rebase, do not reset Iris's accumulated counter.
                lastStartTime = OptionalLong.of(realNs);
                replayModRender_clockOwner = null;
                replayModRender_logicalFrame = 0;
                replayModRender_lastCaptureNs = 0;
            }
            return;
        }

        int frame = handler.getRenderInfo().getFramesDone();
        if (replayModRender_clockOwner == handler && replayModRender_logicalFrame == frame) {
            // Cube faces / stereo eyes share one logical frame. Keep its first delta and counter.
            lastStartTime = OptionalLong.of(realNs);
            ci.cancel();
            return;
        }

        long captureNs = handler.getCurrentCaptureNs();
        long delta = replayModRender_clockOwner == handler
                ? captureNs - replayModRender_lastCaptureNs
                : captureNs - lastStartTime.orElse(captureNs);
        // Iris will compute the capture delta, then store realNs itself. Negative world-time
        // movement and Iris's millisecond quantization / 3600-second rollover stay unchanged.
        lastStartTime = OptionalLong.of(realNs - delta);
        replayModRender_clockOwner = handler;
        replayModRender_logicalFrame = frame;
        replayModRender_lastCaptureNs = captureNs;
    }

    @Inject(method = "reset()V", at = @At("RETURN"), remap = false, require = 1)
    private void replayModRender_resetCaptureClock(CallbackInfo ci) {
        replayModRender_clockOwner = null;
        replayModRender_logicalFrame = 0;
        replayModRender_lastCaptureNs = 0;
    }
}
