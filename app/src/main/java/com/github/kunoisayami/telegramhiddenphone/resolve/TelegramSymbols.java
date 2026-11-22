package com.github.kunoisayami.telegramhiddenphone.resolve;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * Resolves the Telegram internals this module hooks, coping with the fact that R8
 * renames some of them. What survives differs between releases:
 *
 * <ul>
 *   <li><b>12.x</b>: {@code org.telegram.messenger.*} names (e.g. {@code UserConfig},
 *       {@code getCurrentUser}) survive; the {@code TLRPC} hierarchy is flattened and its
 *       fields renamed.</li>
 *   <li><b>13.x</b>: the opposite. {@code org.telegram.tgnet.**} is kept, so
 *       {@code TLRPC$User} and its {@code phone} field keep their names, while
 *       {@code org.telegram.messenger.*} is renamed ({@code UserConfig} became {@code a20}
 *       in 13.3.1).</li>
 * </ul>
 *
 * <p>Strategy, cheapest first:
 * <ol>
 *   <li><b>Names</b> — use the real name when it survives.</li>
 *   <li><b>Reflection</b> — recover an obfuscated <em>type</em> from the signature of a
 *       member whose name survived.</li>
 *   <li><b>DexKit</b> — only when both fail. Results are cached per Telegram versionCode, so
 *       the scan runs once per upgrade.</li>
 * </ol>
 */
public final class TelegramSymbols {

    private static final String TAG = "TelegramHiddenPhone";

    private static final String USER_CONFIG = "org.telegram.messenger.UserConfig";
    private static final String TLRPC_USER = "org.telegram.tgnet.TLRPC$User";

    /**
     * String constants that, together, only {@code UserConfig} uses: its SharedPreferences file
     * ({@code "userconfing"}, sic), the selected-account key and a persisted field. Fewer is not
     * enough: {@code SharedConfig} and the save lambda each share some of them. String literals
     * are never renamed by R8.
     */
    private static final String[] USER_CONFIG_ANCHORS =
            {"userconfing", "selectedAccount", "lastHintsSyncTime"};

    private static final String KEY_USER_CONFIG = "userConfigClass";
    private static final String KEY_PHONE_FIELD = "phoneField";

    private final ClassLoader classLoader;
    private final SignatureCache cache;

    private Class<?> userClass;
    private Class<?> userConfigClass;
    private Method currentUserGetter;

    /** Lazily created; null until something actually needs a dex scan. */
    private DexKitBridge bridge;
    private boolean bridgeAttempted;

    public TelegramSymbols(ClassLoader classLoader, Context hostContext) {
        this.classLoader = classLoader;
        this.cache = new SignatureCache(hostContext);
    }

    /**
     * {@code TLRPC$User}. Looked up by name first (13.x keeps it); on older releases, where it
     * is flattened, it is recovered from the return type of {@code UserConfig.getCurrentUser()}.
     */
    public synchronized Class<?> userClass() {
        if (userClass != null) return userClass;
        try {
            userClass = classLoader.loadClass(TLRPC_USER);
            return userClass;
        } catch (ClassNotFoundException ignored) {
            // obfuscated build; fall through to the signature route
        }
        try {
            Class<?> userConfig = userConfigClass();
            if (userConfig != null) {
                userClass = userConfig.getDeclaredMethod("getCurrentUser").getReturnType();
            }
        } catch (Throwable t) {
            Log.w(TAG, "userClass: reflection failed", t);
        }
        return userClass;
    }

    /**
     * {@code UserConfig}: by name when it survives, else found with DexKit through the string
     * constants it owns and cached per Telegram version.
     */
    public synchronized Class<?> userConfigClass() {
        if (userConfigClass != null) return userConfigClass;

        Class<?> found = loadOrNull(USER_CONFIG);
        if (found == null) {
            String cached = cache.get(KEY_USER_CONFIG);
            if (cached != null) found = loadOrNull(cached);
        }
        if (found == null) {
            found = userConfigViaDexKit();
        }
        userConfigClass = found;
        return found;
    }

    /**
     * The no-arg instance method of {@code UserConfig} that returns the current user. Matched by
     * shape ({@code () -> TLRPC$User}) so it works whatever the method got renamed to.
     */
    public synchronized Method currentUserGetter() {
        if (currentUserGetter != null) return currentUserGetter;

        Class<?> userConfig = userConfigClass();
        Class<?> user = userClass();
        if (userConfig == null || user == null) return null;

        try {
            currentUserGetter = userConfig.getDeclaredMethod("getCurrentUser");
            return currentUserGetter;
        } catch (NoSuchMethodException ignored) {
            // renamed; match by signature
        }

        Method match = null;
        for (Method m : userConfig.getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != user) continue;
            if (match != null) {
                Log.w(TAG, "currentUserGetter: ambiguous, " + match.getName() + " vs " + m.getName());
                return null;
            }
            match = m;
        }
        if (match != null) {
            Log.d(TAG, "currentUserGetter: resolved by signature -> " + match.getName());
        }
        currentUserGetter = match;
        return match;
    }

    /**
     * The {@code phone} field on the user class.
     *
     * <p>On 13.x it is simply named {@code phone}. On releases where R8 renamed it, it is
     * found by reading the live value of {@code UserConfig.getClientPhone()} and matching it
     * against the String fields of the current user object, with DexKit as the last resort.
     */
    public Field phoneField(Object currentUser) {
        Class<?> userCls = userClass();
        if (userCls == null || currentUser == null) return null;

        String cached = cache.get(KEY_PHONE_FIELD);
        if (cached != null) {
            Field f = findStringFieldOrNull(userCls, cached);
            if (f != null) return f;
        }

        Field byName = findStringFieldOrNull(userCls, "phone");
        if (byName != null) {
            Log.d(TAG, "phoneField: resolved by name");
            return byName;
        }

        String phone = clientPhone();
        if (phone != null && !phone.isEmpty()) {
            for (Class<?> c = userCls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        if (phone.equals(f.get(currentUser))) {
                            cache.put(KEY_PHONE_FIELD, f.getName());
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

    /** {@code UserConfig.getClientPhone()} on the active account, or null (name must have survived). */
    private String clientPhone() {
        try {
            Class<?> userConfig = userConfigClass();
            if (userConfig == null) return null;
            Method getInstance = userConfig.getDeclaredMethod("getInstance", int.class);
            Method getClientPhone = userConfig.getDeclaredMethod("getClientPhone");
            int account = 0;
            try {
                Field selected = userConfig.getDeclaredField("selectedAccount");
                selected.setAccessible(true);
                account = selected.getInt(null);
            } catch (Throwable ignored) {
                // fall back to account 0
            }
            return (String) getClientPhone.invoke(getInstance.invoke(null, account));
        } catch (Throwable t) {
            Log.w(TAG, "clientPhone: unavailable", t);
            return null;
        }
    }

    /** DexKit: the class owning every {@link #USER_CONFIG_ANCHORS} string and a {@code () -> User} getter. */
    private Class<?> userConfigViaDexKit() {
        DexKitBridge dk = bridge();
        if (dk == null) return null;
        Class<?> user = userClass == null ? loadOrNull(TLRPC_USER) : userClass;
        try {
            ClassDataList candidates = dk.findClass(FindClass.create().matcher(
                    ClassMatcher.create().usingStrings(
                            Arrays.asList(USER_CONFIG_ANCHORS), StringMatchType.Equals)));
            Class<?> match = null;
            for (ClassData data : candidates) {
                Class<?> c = loadOrNull(data.getName());
                if (c == null) continue;
                // When the user type is known, insist on the getter; it disambiguates
                // against unrelated classes that happen to share a string.
                if (user != null && !hasGetterReturning(c, user)) continue;
                if (match != null) {
                    Log.w(TAG, "userConfigViaDexKit: ambiguous, " + match.getName() + " vs " + c.getName());
                    return null;
                }
                match = c;
            }
            if (match == null) {
                Log.w(TAG, "userConfigViaDexKit: no class matched");
                return null;
            }
            cache.put(KEY_USER_CONFIG, match.getName());
            Log.d(TAG, "userConfigViaDexKit: resolved -> " + match.getName());
            return match;
        } catch (Throwable t) {
            Log.e(TAG, "userConfigViaDexKit: lookup failed", t);
            return null;
        }
    }

    private static boolean hasGetterReturning(Class<?> owner, Class<?> type) {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * DexKit fallback: {@code getClientPhone} reads the phone field directly, so the
     * String field it touches on the User class is the one we want. Needs the method name
     * to have survived.
     */
    private Field phoneFieldViaDexKit(Class<?> userCls) {
        Class<?> userConfig = userConfigClass();
        if (userConfig == null) return null;
        DexKitBridge dk = bridge();
        if (dk == null) return null;
        try {
            MethodDataList found = dk.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create()
                            .declaredClass(userConfig.getName())
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
                cache.put(KEY_PHONE_FIELD, fd.getName());
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

    private Class<?> loadOrNull(String name) {
        try {
            return classLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Field findStringFieldOrNull(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) return null;
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
