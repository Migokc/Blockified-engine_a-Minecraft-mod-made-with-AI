package com.fnfmod.client.input;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A thread-safe queue of timestamped note key transitions.
 *
 * <p>An input source pushes press/release events with the {@link System#nanoTime()}
 * they were detected at; the game thread drains them once per frame and judges
 * each against the song position at its own timestamp. Decoupling detection from
 * the render loop this way is what lets a high-rate source (a background poller)
 * feed precise, frame-rate-independent input through the same path the on-screen
 * GLFW handler uses.
 */
public final class NoteInput {

    /** One key transition: which lane, press or release, and when it happened. */
    public record Event(int lane, boolean press, long nano) {}

    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();

    /** Enqueues a transition. Safe to call from any thread. */
    public void push(int lane, boolean press, long nano) {
        if (lane < 0 || lane > 3) return;
        queue.add(new Event(lane, press, nano));
    }

    /**
     * Hands every pending event to the consumer, oldest first. Ordering by
     * nanoTime matters when a fast source batches several transitions between two
     * frames — they must be judged in the order the player made them.
     */
    public void drain(Consumer<Event> consumer) {
        if (queue.isEmpty()) return;
        List<Event> batch = new ArrayList<>();
        Event event;
        while ((event = queue.poll()) != null) batch.add(event);
        batch.sort(Comparator.comparingLong(Event::nano));
        for (Event ready : batch) consumer.accept(ready);
    }

    /** Drops any pending events, e.g. when gameplay pauses or ends. */
    public void clear() {
        queue.clear();
    }
}
