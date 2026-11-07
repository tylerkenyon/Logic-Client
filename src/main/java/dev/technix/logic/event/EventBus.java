package dev.technix.logic.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public class EventBus {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

    private record Listener(Object owner, int priority, MethodHandle handle, String description) {

    }

    private final Map<Class<? extends Event>, CopyOnWriteArrayList<Listener>> listeners =
        new ConcurrentHashMap<>();


    public void register(Object owner) {
        unregister(owner);

        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Subscribe subscribe = method.getAnnotation(Subscribe.class);
                if (subscribe == null) {
                    continue;
                }
                if (method.getParameterCount() != 1
                        || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    throw new IllegalArgumentException(
                            "@Subscribe method must take exactly one Event parameter: " + method);
                }

                Class<? extends Event> eventType =
                        (Class<? extends Event>) method.getParameterTypes()[0];
                try {
                    method.setAccessible(true);
                    MethodHandle handle = MethodHandles.lookup()
                            .unreflect(method)
                            .bindTo(owner)
                            .asType(MethodType.methodType(void.class, Event.class));

                    CopyOnWriteArrayList<Listener> list =
                            listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
                    list.add(new Listener(owner, subscribe.priority(), handle,
                            type.getSimpleName() + "#" + method.getName()));
                    list.sort(Comparator.<Listener>comparingInt(Listener::priority).reversed());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot access listener " + method, e);
                }
            }
        }
    }
    public void unregister(Object owner) {
        for (List<Listener> list : listeners.values()) {
            list.removeIf(listener -> listener.owner() == owner);
        }
    }

    public void clear() { listeners.clear(); }


    public <T extends Event> T post(T event) {
        List<Listener> list = listeners.get(event.getClass());
        if (list == null) {
            return event;
        }
        for (Listener listener : list) {
            if (event.isCancelled()) {
                break;
            }
            try {
                listener.handle().invokeExact((Event) event);
            } catch (Throwable t) {
                LOGGER.error("Listener {} threw while handling {}",
                        listener.description(), event.getClass().getSimpleName(), t);
            }
        }
        return event;
    }
    }

