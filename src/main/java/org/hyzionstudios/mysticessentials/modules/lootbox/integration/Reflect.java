package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The small reflection kit the LootBox bridges share. Every bridge is a
 * class-presence bridge like {@code ManagedAccountsBridge}: no Mystic sibling is
 * a compile or runtime dependency, the classes are looked up by name through
 * this plugin's class loader, and a missing class or method simply means the
 * integration is absent.
 */
final class Reflect {

    private Reflect() {
    }

    /** The class, or {@code null} when it is not loadable (the plugin is not installed). */
    static Class<?> find(String name) {
        try {
            return Class.forName(name, false, Reflect.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> require(String name) throws ClassNotFoundException {
        return Class.forName(name, false, Reflect.class.getClassLoader());
    }

    static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        return owner.getMethod(name, parameters);
    }

    /** Invokes and unwraps: the target's own exception is rethrown as-is (wrapped if checked). */
    static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }
}
