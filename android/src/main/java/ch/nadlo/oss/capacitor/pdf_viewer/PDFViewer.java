package ch.nadlo.oss.capacitor.pdf_viewer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.getcapacitor.Bridge;
import com.rajat.pdfviewer.PdfViewerActivity;
import com.rajat.pdfviewer.util.saveTo;
import com.rajat.pdfviewer.util.CacheStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class PDFViewer {
    private Bridge bridge = null;

    private static final List<Activity> activeActivities = new ArrayList<>();

    // Orientation lock bookkeeping for the main (Capacitor) activity
    private Integer prevRequestedOrientation = null;
    private boolean mainOrientationLocked = false;

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;

        if (activeActivities.isEmpty()) {
            Application app = (Application) bridge.getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                    if (activity.getClass().getName().equals(PdfViewerActivity.class.getName())) {
                        activeActivities.add(activity);

                        // immersive + keep screen on (your existing code)
                        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        View decorView = activity.getWindow().getDecorView();
                        decorView.setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        );

                        // 🔒 Lock the viewer to its current orientation
                        lockActivityToItsCurrentOrientation(activity);
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    if (activity.getClass().getName().equals(PdfViewerActivity.class.getName())) {
                        // Belt-and-suspenders: re-assert the lock after resume
                        lockActivityToItsCurrentOrientation(activity);
                    }
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    if (activity.getClass().getName().equals(PdfViewerActivity.class.getName())) {
                        activeActivities.remove(activity);
                        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        // Restore main activity orientation when viewer closes
                        unlockMainActivityOrientation();
                    }
                }

                public void onActivityStarted(@NonNull Activity activity) {
                }

                public void onActivityStopped(@NonNull Activity activity) {
                }

                public void onActivityPaused(@NonNull Activity activity) {
                }

                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                }
            });
        }
    }

    /* ───────────────────────────── Orientation helpers ───────────────────────────── */

    private static void lockActivityToItsCurrentOrientation(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            // Fallback for very old devices: lock to the exact current orientation
            int rotation = ((WindowManager) a.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRotation();
            int o = a.getResources().getConfiguration().orientation;

            int req;
            if (o == Configuration.ORIENTATION_LANDSCAPE) {
                req = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90)
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            } else {
                req = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90)
                        ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
            a.setRequestedOrientation(req);
        }
    }

    private void unlockMainActivityOrientation() {
        if (!mainOrientationLocked || bridge == null) return;
        final Activity main = bridge.getActivity();
        if (main == null) return;

        final int restore = (prevRequestedOrientation == null
                || prevRequestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED)
                ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : prevRequestedOrientation;

        new Handler(Looper.getMainLooper()).post(() -> {
            main.setRequestedOrientation(restore);
            prevRequestedOrientation = null;
            mainOrientationLocked = false;
        });
    }

    /* ───────────────────────────── Public API ───────────────────────────── */

    public void openViewer(String url, String title) {
        // Close any existing PDF activities (safety)
        this.close();

        Map<String, String> headers = new HashMap<>();

        Intent activeIntent = PdfViewerActivity.Companion.launchPdfFromUrl(
                this.bridge.getContext(),
                url,
                title,
                saveTo.DOWNLOADS,
                false,               // enable download
                true,                // enable swipe
                headers,
                null,
                CacheStrategy.DISABLE_CACHE // keeps the fix you found
        );

        this.bridge.getActivity().startActivity(activeIntent);
    }

    public void close() {
        for (Activity activity : new ArrayList<>(activeActivities)) {
            activity.finish();
        }
        // If close() is called by JS, also restore orientation immediately
        unlockMainActivityOrientation();
    }
}
