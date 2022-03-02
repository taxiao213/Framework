package vl.vision.home.util.data.wifi;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class ThreadUtils {

    private static volatile Thread sMainThread;

    private static volatile Handler sMainThreadHandler;

    private static volatile ExecutorService sThreadExecutor;

    public static boolean isMainThread() {
        if (sMainThread == null)
            sMainThread = Looper.getMainLooper().getThread();
        return (Thread.currentThread() == sMainThread);
    }

    public static Handler getUiThreadHandler() {
        if (sMainThreadHandler == null)
            sMainThreadHandler = new Handler(Looper.getMainLooper());
        return sMainThreadHandler;
    }

    public static void ensureMainThread() {
        if (!isMainThread())
            throw new RuntimeException("Must be called on the UI thread");
    }

    public static Future postOnBackgroundThread(Runnable runnable) {
        return getThreadExecutor().submit(runnable);
    }

    public static Future postOnBackgroundThread(Callable<?> callable) {
        return getThreadExecutor().submit(callable);
    }

    public static void postOnMainThread(Runnable runnable) {
        getUiThreadHandler().post(runnable);
    }

    private static synchronized ExecutorService getThreadExecutor() {
        if (sThreadExecutor == null)
            sThreadExecutor = Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors());
        return sThreadExecutor;
    }

}
