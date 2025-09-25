package ch.nadlo.oss.capacitor.pdf_viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerFragment extends Fragment {

    public static final String ARG_URL = "url";
    public static final String ARG_TOP_MARGIN = "top";

    private ViewPager2 viewPager;
    private ProgressBar progress;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private File tempFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String url;
    private int topMargin;

    public static PdfViewerFragment newInstance(String url, int topMargin) {
        PdfViewerFragment fragment = new PdfViewerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putInt(ARG_TOP_MARGIN, topMargin);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            url = getArguments().getString(ARG_URL);
            topMargin = getArguments().getInt(ARG_TOP_MARGIN, 0);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pdf_viewer, container, false);

        viewPager = view.findViewById(R.id.viewPager);
        progress = view.findViewById(R.id.progress);

        // Adjust top margin if provided
        if (topMargin > 0) {
            // Convert the dp value from JS to pixels for the native view
            final float scale = getResources().getDisplayMetrics().density;
            int topMarginPx = (int) (topMargin * scale + 0.5f);

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            params.topMargin = topMarginPx;
            view.setLayoutParams(params);
        }

        loadPdf();

        return view;
    }

    private void loadPdf() {
        if (url == null) {
            // Handle error: URL is missing
            getParentFragmentManager().beginTransaction().remove(this).commit();
            return;
        }

        io.execute(() -> {
            try {
                File pdfFile;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    pdfFile = downloadToCache(getContext(), url);
                    tempFile = pdfFile;
                } else if (url.startsWith("content://")) {
                    pdfFile = copyContentUriToCache(getContext(), Uri.parse(url));
                    tempFile = pdfFile;
                } else {
                    pdfFile = url.startsWith("file://") ?
                            new File(Uri.parse(url).getPath()) : new File(url);
                }

                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(fileDescriptor);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        viewPager.setAdapter(new PdfPagerAdapter(pdfRenderer));
                        viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
                        progress.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                // Handle error
                e.printStackTrace();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        getParentFragmentManager().beginTransaction().remove(this).commit();
                    });
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
            }
            if (fileDescriptor != null) {
                fileDescriptor.close();
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            io.shutdownNow();
        }
    }

    private static File downloadToCache(Context ctx, String urlStr) throws Exception {
        File out = File.createTempFile("pdf_viewer_", ".pdf", ctx.getCacheDir());
        try (InputStream in = new URL(urlStr).openStream();
             FileOutputStream outStream = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                outStream.write(buf, 0, n);
            }
        }
        return out;
    }

    private static File copyContentUriToCache(Context ctx, Uri uri) throws Exception {
        File out = File.createTempFile("pdf_viewer_", ".pdf", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream outStream = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                outStream.write(buf, 0, n);
            }
        }
        return out;
    }

    private static class PdfPagerAdapter extends RecyclerView.Adapter<PdfPagerAdapter.PdfPageViewHolder> {
        private final PdfRenderer renderer;

        PdfPagerAdapter(PdfRenderer renderer) {
            this.renderer = renderer;
        }

        @NonNull
        @Override
        public PdfPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PhotoView pv = new PhotoView(parent.getContext());
            pv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            pv.setBackgroundColor(Color.WHITE);
            pv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pv.setAdjustViewBounds(false);
            return new PdfPageViewHolder(pv);
        }

        @Override
        public void onBindViewHolder(@NonNull PdfPageViewHolder holder, int position) {
            if (renderer == null || position < 0 || position >= getItemCount()) {
                return;
            }
            try (PdfRenderer.Page page = renderer.openPage(position)) {
                DisplayMetrics dm = holder.itemView.getResources().getDisplayMetrics();
                int viewWidth = dm.widthPixels;
                int viewHeight = dm.heightPixels;

                // Create a bitmap with the same aspect ratio as the page
                Bitmap bmp = Bitmap.createBitmap(viewWidth, (int) (viewWidth * ((float) page.getHeight() / page.getWidth())), Bitmap.Config.ARGB_8888);

                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                holder.photoView.setImageBitmap(bmp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public int getItemCount() {
            return renderer != null ? renderer.getPageCount() : 0;
        }

        static class PdfPageViewHolder extends RecyclerView.ViewHolder {
            final PhotoView photoView;

            PdfPageViewHolder(@NonNull PhotoView itemView) {
                super(itemView);
                photoView = itemView;
            }
        }
    }
}
