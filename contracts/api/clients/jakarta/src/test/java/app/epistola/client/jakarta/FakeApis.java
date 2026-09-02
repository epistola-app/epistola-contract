// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds stand-ins for the generated API interfaces.
 *
 * <p>A dynamic proxy rather than a hand-written implementation: these interfaces carry dozens of
 * operations that grow with the contract, and a test that only needs {@code getTemplate} should not
 * have to be edited every time an unrelated endpoint is added. Any method the test did not stub
 * throws, so an unexpected call is loud rather than silently returning null.
 */
public final class FakeApis {

    /**
     * A proxy for {@code apiInterface} where each named method is answered by the matching
     * function, which receives the call's arguments.
     */
    @SuppressWarnings("unchecked")
    public static <T> T of(Class<T> apiInterface, Map<String, Function<Object[], Object>> stubs) {
        return (T) Proxy.newProxyInstance(
                apiInterface.getClassLoader(), new Class<?>[] {apiInterface}, (proxy, method, args) -> {
                    Function<Object[], Object> stub = stubs.get(method.getName());
                    if (stub != null) {
                        return stub.apply(args == null ? new Object[0] : args);
                    }
                    return objectMethodOrFail(apiInterface, proxy, method, args);
                });
    }

    private static Object objectMethodOrFail(Class<?> apiInterface, Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "Fake" + apiInterface.getSimpleName();
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                throw new UnsupportedOperationException(
                        apiInterface.getSimpleName() + "." + method.getName() + " was not stubbed for this test");
        }
    }

    private FakeApis() {
    }
}
