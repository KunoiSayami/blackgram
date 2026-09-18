package com.github.kunoisayami.telegramhiddenphone.resolve;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * Resolves the Telegram internals this module hooks, coping with the fact that R8
 * renames some of them.
 *
 * <p>Telegram 12.x is only <em>partially</em> obfuscated: most
 * {@code org.telegram.messenger.*} class names and method names survive, while the
 * whole {@code org.telegram.tgnet.TLRPC} hierarchy is flattened into single-letter
 * classes and its fields are renamed.
 *
 * <p>Strategy, cheapest first:
 * <ol>
 *   <li><b>Reflection</b> — recover obfuscated <em>types</em> by reading the signature of a
 *       method whose <em>name</em> survived. Free, instant, no dependency.</li>
 *   <li><b>DexKit</b> — only when reflection fails (i.e. the name was obfuscated too).
 *       Results are cached per Telegram versionCode, so the scan runs once per upgrade.</li>
 * </ol>
 */
public final class TelegramSymbols {

    private static final String TAG = "TelegramHiddenPhone";

    private final ClassLoader classLoader;
    private final SignatureCache cache;

    /** Lazily created; null until something actually needs a dex scan. */
    private DexKitBridge bridge;
    private boolean bridgeAttempted;

    public TelegramSymbols(ClassLoader classLoader, Context hostContext) {
        this.classLoader = classLoader;
        this.cache = new SignatureCache(hostContext);
    }

    /**
     * {@code TLRPC$User}, recovered from the return type of {@code UserConfig.getCurrentUser()}.
     * The method name survives obfuscation, so this needs no dex scan.
     */
    public Class<?> userClass() {
        try {
            Class<?> userConfig = classLoader.loadClass("org.telegram.messenger.UserConfig");
            return userConfig.getDeclaredMethod("getCurrentUser").getReturnType();
        } catch (Throwable t) {
            Log.w(TAG, "userClass: reflection failed", t);
            return null;
        }
    }

    /**
     * The {@code phone} field on {@code TLRPC$User}. R8 renames it to a single letter,
     * so it cannot be looked up by name.
     *
     * <p>Resolved by reading the live value of {@code UserConfig.getClientPhone()} — whose
     * name survives — and matching it against the String fields of the current user object.
     * That derives the letter at runtime instead of pinning it to one release.
     * Falls back to DexKit when no account is signed in and there is no value to match.
     */
    public Field phoneField(Object currentUser) {
        Class<?> userCls = userClass();
        if (userCls == null || currentUser == null) return null;

        String cached = cache.get("phoneField");
        if (cached != null) {
            Field f = findFieldOrNull(userCls, cached);
            if (f != null) return f;
        }

        String phone = clientPhone();
        if (phone != null && !phone.isEmpty()) {
            for (Class<?> c = userCls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        if (phone.equals(f.get(currentUser))) {
                            cache.put("phoneField", f.getName());
                            Log.d(TAG, "phoneField: resolved by value -> " + f.getName());
                            return f;
                        }
                    } catch (Throwable ignored) {
                        // not readable; keep scanning
                    }
                }
            }
            Log.w(TAG, "phoneField: no String field matched getClientPhone()");
        }

        return phoneFieldViaDexKit(userCls);
    }

    /** {@code UserConfig.getClientPhone()} on the active account, or null. */
    private String clientPhone() {
        try {
            Class<?> userConfig = classLoader.loadClass("org.telegram.messenger.UserConfig");
            Method getInstance = userConfig.getDeclaredMethod("getInstance", int.class);
            Method getClientPhone = userConfig.getDeclaredMethod("getClientPhone");
            Method getSelected = userConfig.getDeclaredMethod("selectedAccount");
            Object instance = getInstance.invoke(null, getSelected.invoke(null));
            return (String) getClientPhone.invoke(instance);
        } catch (Throwable t) {
            // selectedAccount is a field on some builds; retry via account 0.
            try {
                Class<?> userConfig = classLoader.loadClass("org.telegram.messenger.UserConfig");
                Object instance = userConfig.getDeclaredMethod("getInstance", int.class).invoke(null, 0);
                return (String) userConfig.getDeclaredMethod("getClientPhone").invoke(instance);
            } catch (Throwable t2) {
                Log.w(TAG, "clientPhone: unavailable", t2);
                return null;
            }
        }
    }

    /**
     * DexKit fallback: {@code getClientPhone} reads the phone field directly, so the
     * String field it touches on the User class is the one we want.
     */
    private Field phoneFieldViaDexKit(Class<?> userCls) {
        DexKitBridge dk = bridge();
        if (dk == null) return null;
        try {
            MethodDataList found = dk.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create()
                            .declaredClass("org.telegram.messenger.UserConfig")
                            .name("getClientPhone")
                            .returnType(String.class)));
            if (found.isEmpty()) {
                Log.w(TAG, "phoneField: DexKit could not find getClientPhone");
                return null;
            }
            for (UsingFieldData used : found.get(0).getUsingFields()) {
                FieldData fd = used.getField();
                if (!"java.lang.String".equals(fd.getTypeName())) continue;
                if (!userCls.getName().equals(fd.getDeclaredClassName())) continue;
                cache.put("phoneField", fd.getName());
                Log.d(TAG, "phoneField: resolved by DexKit -> " + fd.getName());
                Field f = fd.getFieldInstance(classLoader);
                f.setAccessible(true);
                return f;
            }
            Log.w(TAG, "phoneField: DexKit found no matching String field");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "phoneField: DexKit lookup failed", t);
            return null;
        }
    }

    private static Field findFieldOrNull(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Creates the DexKit bridge on first need; a failure here is non-fatal. */
    private DexKitBridge bridge() {
        if (bridgeAttempted) return bridge;
        bridgeAttempted = true;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(classLoader, true);
            Log.d(TAG, "bridge: DexKit initialised");
        } catch (Throwable t) {
            Log.e(TAG, "bridge: DexKit unavailable, obfuscated members stay unresolved", t);
            bridge = null;
        }
        return bridge;
    }

    /** Releases native DexKit resources. Safe to call when the bridge was never created. */
    public void close() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }
}
