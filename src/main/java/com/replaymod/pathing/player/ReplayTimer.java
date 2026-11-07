package com.replaymod.pathing.player;

import com.replaymod.core.mixin.TimerAccessor;
import com.replaymod.core.utils.WrappedTimer;
import de.johni0702.minecraft.gui.utils.Event;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Wrapper around the current timer that prevents the timer from advancing by itself.
 */
public class ReplayTimer extends WrappedTimer {
    //#if MC>=12003
    private final RenderTickCounter.Dynamic state = new RenderTickCounter.Dynamic(20, 0, f -> f);
    //#elseif MC>=11400
    //$$ private final RenderTickCounter state = new RenderTickCounter(0, 0);
    //#else
    //$$ private final Timer state = new Timer(0);
    //#endif

    //#if MC>=11600
    public int ticksThisFrame;
    //#endif

    public ReplayTimer(RenderTickCounter wrapped) {
        super(wrapped);
    }

    @Override
    // This should be handled by Remap but it isn't (was handled before a9724e3).
    //#if MC>=11400
    public
    //#if MC>=11600
    int
    //#else
    //$$ void
    //#endif
    beginRenderTick(
    //#else
    //$$ public void updateTimer(
    //#endif
            //#if MC>=11400
            long sysClock
            //#endif
    ) {
        copy(this, state); // Save our current state
        try {
            //#if MC>=11600
            ticksThisFrame =
            //#endif
            wrapped.beginRenderTick(
                    //#if MC>=11400
                    sysClock, tickEnabled
                    //#endif
            ); // Update current state
        } finally {
            copy(state, this); // Restore our old state
            // Keep real-frame timing current without allowing it to advance replay interpolation.
            TimerAccessor timer = (TimerAccessor) this;
            timer.setLastDurationRaw((sysClock - timer.getTimeMillisRaw()) / timer.getTickLength());
            timer.setTimeMillisRaw(sysClock);
            if (tickEnabled) {
                timer.setLastSyncSysClock(sysClock);
                UpdatedCallback.EVENT.invoker().onUpdate();
            } else {
                // Render-only calls must still pump InputReplayTimer, but must not seek the timeline.
                ticksThisFrame = 0;
            }
        }
        //#if MC>=11600
        return ticksThisFrame;
        //#endif
    }

    @Override
    public void tick(boolean paused) {
        // Each counter retains its own interpolation, but both must see pause transitions.
        super.tick(paused);
        wrapped.tick(paused);
    }

    @Override
    public void setTickFrozen(boolean frozen) {
        super.setTickFrozen(frozen);
        wrapped.setTickFrozen(frozen);
    }

    public RenderTickCounter.Dynamic getWrapped() {
        return wrapped;
    }

    public interface UpdatedCallback {
        Event<UpdatedCallback> EVENT = Event.create((listeners) ->
                () -> {
                    for (UpdatedCallback listener : listeners) {
                        listener.onUpdate();
                    }
                }
        );
        void onUpdate();
    }
}
