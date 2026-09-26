package com.hashicorp.demo;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A short log of what happened, so the page has something to show. */
@Component
public class DemoEvents {

    public record Event(String at, String kind, String detail) {}

    private final Deque<Event> events = new ArrayDeque<>();

    public synchronized void log(String kind, String detail) {
        if (events.size() >= 40) {
            events.removeFirst();
        }
        events.addLast(new Event(Instant.now().toString().substring(11, 19), kind, detail));
    }

    public synchronized List<Event> recent() {
        List<Event> copy = new ArrayList<>(events);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
