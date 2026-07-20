package me.pewa.event;

import java.util.*;

public class EventBus {
    private Map<Class<?>, List<EventListener>> listeners = new HashMap<>();
    
    public void subscribe(Class<?> eventType, EventListener listener) {
        listeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(listener);
    }
    
    public void unsubscribe(Class<?> eventType, EventListener listener) {
        List<EventListener> list = listeners.get(eventType);
        if (list != null) {
            list.remove(listener);
        }
    }
    
    public void post(Event event) {
        List<EventListener> list = listeners.get(event.getClass());
        if (list != null) {
            for (EventListener listener : list) {
                listener.onEvent(event);
            }
        }
    }
}
