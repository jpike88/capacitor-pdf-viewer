package ch.nadlo.oss.capacitor.pdf_viewer;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;

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

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;

        // Register global activity lifecycle callbacks if not already registered
        if (activeActivities.isEmpty()) {
            Application app = (Application) bridge.getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                    if (activity.getClass().getName().equals(PdfViewerActivity.class.getName())) {
                        activeActivities.add(activity);
                        // Add FLAG_KEEP_SCREEN_ON when PdfViewerActivity is created
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
                    }
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    if (activity.getClass().getName().equals(PdfViewerActivity.class.getName())) {
                        activeActivities.remove(activity);
                        // Clear FLAG_KEEP_SCREEN_ON when PdfViewerActivity is destroyed
                        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }

                // Other lifecycle methods can remain empty
                public void onActivityStarted(@NonNull Activity activity) {
                }

                public void onActivityStopped(@NonNull Activity activity) {
                }

                public void onActivityResumed(@NonNull Activity activity) {
                }

                public void onActivityPaused(@NonNull Activity activity) {
                }

                public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                }
            });
        }
    }

    public void openViewer(String url, String title) {
        this.close();

        Map<String, String> headers = new HashMap<>();

        // Create an intent with unique extras or identifiers
        Intent activeIntent = PdfViewerActivity.Companion.launchPdfFromUrl(
                this.bridge.getContext(),
                url,
                title,
                saveTo.DOWNLOADS,
                false,
                true,
                headers,
                null,
                CacheStrategy.MAXIMIZE_PERFORMANCE
        );

        this.bridge.getActivity().startActivity(activeIntent);
    }

    public void close() {
        for (Activity activity : activeActivities) {
            activity.finish();
        }
    }
}
