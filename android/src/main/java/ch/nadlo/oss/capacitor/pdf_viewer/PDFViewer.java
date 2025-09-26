package ch.nadlo.oss.capacitor.pdf_viewer;

import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.getcapacitor.Bridge;
import com.getcapacitor.PluginCall;

public class PDFViewer {

    private Bridge bridge;
    private static final String FRAGMENT_TAG = "PdfViewerFragmentTag";

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    public void openViewer(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL is required.");
            return;
        }
        int topMargin = call.getInt("top", 0);

        // Ensure we're running on the UI thread
        bridge.getActivity().runOnUiThread(() -> {
            // Find the root view of the activity
            ViewGroup rootView = (ViewGroup) bridge.getActivity().getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                call.reject("Could not find root view.");
                return;
            }

            // Check if fragment is already added, if so, remove it first
            Fragment existingFragment = bridge.getActivity().getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
            if (existingFragment != null) {
                bridge.getActivity().getSupportFragmentManager().beginTransaction().remove(existingFragment).commit();
            }

            // Create and add the new fragment
            PdfViewerFragment fragment = PdfViewerFragment.newInstance(url, topMargin);
            FragmentTransaction transaction = bridge.getActivity().getSupportFragmentManager().beginTransaction();
            // Use the root view's ID to add the fragment
            transaction.add(rootView.getId(), fragment, FRAGMENT_TAG);
            transaction.addToBackStack(null); // Allows closing with the back button
            transaction.commit();
            call.resolve();
        });
    }

    public void closeViewer(PluginCall call) {
        bridge.getActivity().runOnUiThread(() -> {
            Fragment fragment = bridge.getActivity().getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
            if (fragment != null) {
                bridge.getActivity().getSupportFragmentManager().beginTransaction().remove(fragment).commit();
            }
            call.resolve();
        });
    }
}
