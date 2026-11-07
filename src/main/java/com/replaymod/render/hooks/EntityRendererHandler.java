package com.replaymod.render.hooks;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.events.PostRenderCallback;
import com.replaymod.core.events.PreRenderCallback;
import com.replaymod.core.events.PreRenderHandCallback;
import com.replaymod.core.versions.MCVer;
import com.replaymod.render.RenderSettings;
import com.replaymod.render.Setting;
import com.replaymod.render.capturer.CaptureData;
import com.replaymod.render.capturer.RenderInfo;
import com.replaymod.render.capturer.WorldRenderer;
import com.replaymod.render.mixin.GameRendererAccessor;
import com.replaymod.replay.ReplayModReplay;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;

import java.io.IOException;

public class EntityRendererHandler extends EventRegistrations implements WorldRenderer {
    public final MinecraftClient mc = MCVer.getMinecraft();

    protected final RenderSettings settings;
    private final RenderInfo renderInfo;
    public CaptureData data;
    public boolean omnidirectional;
    private final long startTime;

    // Valid only during the actual capture render, not progress GUIs or ordinary game frames.
    private boolean captureActive;
    private long currentCaptureNs;

    public EntityRendererHandler(RenderSettings settings, RenderInfo renderInfo) {
        this.settings = settings;
        this.renderInfo = renderInfo;
        this.startTime = Util.getMeasuringTimeNano();

        on(PreRenderHandCallback.EVENT, () -> omnidirectional);
        ((IEntityRenderer) mc.gameRenderer).replayModRender_setHandler(this);
        register();
    }

    @Override
    public void renderWorld(final float partialTicks, CaptureData data) {
        this.data = data;
        long offsetMillis;
        if (ReplayMod.instance.getSettingsRegistry().get(Setting.FRAME_TIME_FROM_WORLD_TIME)) {
            offsetMillis = ReplayModReplay.instance.getReplayHandler().getReplaySender().currentTimeStamp();
        } else {
            offsetMillis = renderInfo.getFramesDone() * 1_000L / settings.getFramesPerSecond();
        }
        long frameStartTimeNano = startTime + offsetMillis * 1_000_000L;
        renderWorld(partialTicks, frameStartTimeNano);
    }

    public void renderWorld(float partialTicks, long finishTimeNano) {
        PreRenderCallback.EVENT.invoker().preRender();

        if (mc.world != null && mc.player != null) {
            // Capture interpolation is explicit, while duration getters retain their normal units.
            // Do not advance the Dynamic timer here: RenderInfo has already advanced this output frame.
            RenderTickCounter liveCounter = mc.getRenderTickCounter();
            float lastFrameDuration = liveCounter.getLastFrameDuration();
            float lastDuration = liveCounter.getLastDuration();
            RenderTickCounter captureCounter = new RenderTickCounter() {
                @Override
                public float getLastFrameDuration() {
                    return lastFrameDuration;
                }

                @Override
                public float getTickDelta(boolean ignoreFreeze) {
                    return partialTicks;
                }

                @Override
                public float getLastDuration() {
                    return lastDuration;
                }
            };
            GameRendererAccessor gameRenderer = (GameRendererAccessor) mc.gameRenderer;
            Screen orgScreen = mc.currentScreen;
            boolean orgPauseOnLostFocus = mc.options.pauseOnLostFocus;
            boolean orgRenderHand = gameRenderer.getRenderHand();
            try {
                mc.currentScreen = null; // do not render the progress GUI
                mc.options.pauseOnLostFocus = false;
                if (omnidirectional) {
                    gameRenderer.setRenderHand(false);
                }

                currentCaptureNs = finishTimeNano;
                captureActive = true;
                try {
                    mc.gameRenderer.render(captureCounter, true);
                } finally {
                    captureActive = false;
                    currentCaptureNs = 0;
                }
            } finally {
                mc.currentScreen = orgScreen;
                mc.options.pauseOnLostFocus = orgPauseOnLostFocus;
                gameRenderer.setRenderHand(orgRenderHand);
            }
        }

        PostRenderCallback.EVENT.invoker().postRender();
    }

    @Override
    public void close() throws IOException {
        captureActive = false;
        currentCaptureNs = 0;
        ((IEntityRenderer) mc.gameRenderer).replayModRender_setHandler(null);
        unregister();
    }

    @Override
    public void setOmnidirectional(boolean omnidirectional) {
        this.omnidirectional = omnidirectional;
    }

    public RenderSettings getSettings() {
        return this.settings;
    }

    public RenderInfo getRenderInfo() {
        return this.renderInfo;
    }

    public boolean isCaptureActive() {
        return captureActive;
    }

    public long getCurrentCaptureNs() {
        return currentCaptureNs;
    }

    public interface IEntityRenderer {
        void replayModRender_setHandler(EntityRendererHandler handler);
        EntityRendererHandler replayModRender_getHandler();
    }
}
