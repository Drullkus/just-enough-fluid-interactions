package us.drullk.jefi.devtest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;

/**
 * The steps of one client-driven test, in order. One step is current at a time. When a step completes, the
 * next step runs on the same tick.
 */
final class TickSteps {
    /** One step of a test. Returns true when the step is complete. */
    private interface Step {
        boolean tick(Minecraft mc);
    }

    private final List<Step> steps = new ArrayList<>();
    private int next;

    /** Waits until the condition holds. */
    TickSteps until(Predicate<Minecraft> ready) {
        return until(ready, mc -> {
        });
    }

    /** Waits until the condition holds, then runs the action. */
    TickSteps until(Predicate<Minecraft> ready, Consumer<Minecraft> action) {
        steps.add(mc -> {
            if (!ready.test(mc)) {
                return false;
            }
            action.accept(mc);
            return true;
        });
        return this;
    }

    /** Waits the given number of ticks, then runs the action. */
    TickSteps after(int ticks, Consumer<Minecraft> action) {
        steps.add(new Step() {
            private int remaining = ticks;

            @Override
            public boolean tick(Minecraft mc) {
                if (--remaining > 0) {
                    return false;
                }
                action.accept(mc);
                return true;
            }
        });
        return this;
    }

    /** Runs the action now, then again every given number of ticks, until the action returns false. */
    TickSteps repeat(int ticks, Predicate<Minecraft> action) {
        steps.add(new Step() {
            private int remaining;

            @Override
            public boolean tick(Minecraft mc) {
                if (--remaining > 0) {
                    return false;
                }
                remaining = ticks;
                return !action.test(mc);
            }
        });
        return this;
    }

    /** Ends the test. No further step runs. */
    void stop() {
        next = steps.size();
    }

    void tick(Minecraft mc) {
        while (next < steps.size() && steps.get(next).tick(mc)) {
            next++;
        }
    }
}
