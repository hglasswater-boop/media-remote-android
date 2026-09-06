import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Runs via adb app_process with the APK on CLASSPATH. No playback, network, or app-data writes. */
public final class QuestionsIdentityRegression {
    private static final String EXPECTED = "mO5kvldneUM";

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static SharedPreferences preferences() {
        Map<String, Object> values = new HashMap<>();
        Object editor = Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[] { SharedPreferences.Editor.class }, (proxy, method, args) -> {
                if (method.getName().startsWith("put")) {
                    values.put((String) args[0], args[1]);
                    return proxy;
                }
                if (method.getName().equals("apply")) return null;
                if (method.getName().equals("commit")) return true;
                throw new UnsupportedOperationException(method.getName());
            });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[] { SharedPreferences.class }, (proxy, method, args) -> {
                if (method.getName().equals("edit")) return editor;
                if (method.getName().startsWith("get") && args != null && args.length == 2)
                    return values.getOrDefault((String) args[0], args[1]);
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Object snapshot(Class<?> type, String title, String artist, long duration) throws Exception {
        return type.getConstructor(boolean.class, String.class, String.class, String.class,
            String.class, boolean.class, int.class, float.class, long.class, long.class,
            long.class, String.class, int.class, int.class, java.util.List.class)
            .newInstance(true, "", title, artist, "", true, 3, 1f, 0L, 0L, duration,
                "com.google.android.apps.youtube.music", -1, 0, java.util.Collections.emptyList());
    }

    public static void main(String[] args) throws Exception {
        try {
            runRegression();
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void runRegression() throws Exception {
        SharedPreferences prefs = preferences();
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        Class<?> function = Class.forName("kotlin.jvm.functions.Function1");
        Object callback = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[] { function },
            (proxy, method, parameters) -> null);
        Class<?> sessionType = Class.forName("dev.mediaremote.dial.YouTubeLoungeSession");
        Object session = sessionType.getConstructor(Context.class, String.class, function)
            .newInstance(context, "Regression", callback);
        Class<?> snapshotType = Class.forName("dev.mediaremote.media.MediaSnapshot");
        Object baseline = snapshot(snapshotType, "On Your Side", "", 235000L);
        Object selected = snapshot(snapshotType, "Questions", "Far Caspian", 221000L);
        field(session, "lastMediaSnapshot", baseline);
        field(session, "senderSelectionBaseline", baseline);
        field(session, "senderExpectedVideoId", EXPECTED);
        field(session, "senderSelectionDeadlineMs", Long.MAX_VALUE);
        field(session, "senderSelectionCommandAccepted", true);
        field(session, "currentVideoId", EXPECTED);
        field(session, "currentListId", "RQregression");
        field(session, "currentVideoIds", java.util.Collections.singletonList(EXPECTED));
        field(session, "currentIndex", 0);

        Method reconcile = sessionType.getDeclaredMethod("syncCurrentVideo", snapshotType, boolean.class, snapshotType);
        reconcile.setAccessible(true);
        reconcile.invoke(session, selected, true, baseline);
        if (!Boolean.TRUE.equals(field(session, "currentVideoConfirmed")))
            throw new AssertionError("Selection was not confirmed before publication");

        Method publish = sessionType.getDeclaredMethod("sendNowPlaying", Integer.class, snapshotType);
        publish.setAccessible(true);
        publish.invoke(session, null, selected);
        if (!EXPECTED.equals(field(session, "currentVideoId")) ||
            !Boolean.TRUE.equals(field(session, "currentVideoConfirmed")))
            throw new AssertionError("Publication discarded the selected Questions identity");
        if (!"RQregression".equals(field(session, "currentListId")) ||
            !Integer.valueOf(0).equals(field(session, "currentIndex")))
            throw new AssertionError("Publication discarded playlist context");
        System.out.println("PASS: publication retains selected Questions ID and playlist context");
    }
}
