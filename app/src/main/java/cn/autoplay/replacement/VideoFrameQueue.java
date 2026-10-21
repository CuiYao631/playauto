package cn.autoplay.replacement;

import java.util.ArrayDeque;

final class VideoFrameQueue {
    static final class Entry {
        final VideoFrame frame;
        final long generation;

        Entry(VideoFrame frame, long generation) {
            this.frame = frame;
            this.generation = generation;
        }
    }

    private final ArrayDeque<Entry> frames = new ArrayDeque<>();
    private int capacity;
    private long generation;

    VideoFrameQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Video queue capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean offer(VideoFrame frame) {
        boolean overflow = frames.size() == capacity;
        if (overflow) clear();
        frames.addLast(new Entry(frame, generation));
        return !overflow;
    }

    synchronized Entry poll() { return frames.pollFirst(); }
    synchronized long generation() { return generation; }

    synchronized boolean setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Video queue capacity must be positive");
        this.capacity = capacity;
        if (frames.size() <= capacity) return true;
        clear();
        return false;
    }

    synchronized void clear() {
        frames.clear();
        generation++;
    }
}
