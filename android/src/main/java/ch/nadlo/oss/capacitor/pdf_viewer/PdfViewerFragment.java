package ch.nadlo.oss.capacitor.pdf_viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
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
import com.github.chrisbanes.photoview.PhotoViewAttacher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerFragment extends Fragment {

    public static final String ARG_URL = "url";
    public static final String ARG_TOP_MARGIN = "top";
    private static final String TAG = "PDF_VIEWER_DEBUG";

    private ViewPager2 viewPager;
    private ProgressBar progress;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private File tempFile; // cleanup
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String url;
    private int topMargin;

    // Shared state for zoom and pan across all pages
    private final Matrix sharedMatrix = new Matrix();
    private boolean isInitialScaleSet = false;

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
            Log.e(TAG, "loadPdf: URL is null, cannot open PDF.");
            getParentFragmentManager().beginTransaction().remove(this).commit();
            return;
        }

        io.execute(
            () -> {
                try {
                    File pdfFile;
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        pdfFile = downloadToCache(getContext(), url);
                        tempFile = pdfFile;
                    } else if (url.startsWith("content://")) {
                        pdfFile = copyContentUriToCache(getContext(), Uri.parse(url));
                        tempFile = pdfFile;
                    } else {
                        pdfFile = url.startsWith("file://") ? new File(Uri.parse(url).getPath()) : new File(url);
                    }

                    fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                    pdfRenderer = new PdfRenderer(fileDescriptor);

                    if (getActivity() != null) {
                        getActivity()
                            .runOnUiThread(
                                () -> {
                                    viewPager.setAdapter(new PdfPagerAdapter(pdfRenderer, this));
                                    viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
                                    progress.setVisibility(View.GONE);
                                }
                            );
                    }
                } catch (Exception e) {
                    Log.e(TAG, "loadPdf: Failed to load or render PDF.", e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            getParentFragmentManager().beginTransaction().remove(this).commit();
                        });
                    }
                }
            }
        );
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
            Log.e(TAG, "onDestroy: Error while closing resources.", e);
        } finally {
            io.shutdownNow();
        }
    }

    private static File downloadToCache(Context ctx, String urlStr) throws Exception {
        File out = File.createTempFile("pdf_viewer_", ".pdf", ctx.getCacheDir());
        try (InputStream in = new URL(urlStr).openStream(); FileOutputStream outStream = new FileOutputStream(out)) {
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
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream outStream = new FileOutputStream(out)) {
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
        private final PdfViewerFragment fragment;

        PdfPagerAdapter(PdfRenderer renderer, PdfViewerFragment fragment) {
            this.renderer = renderer;
            this.fragment = fragment;
        }

        @NonNull
        @Override
        public PdfPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Log.d(TAG, "onCreateViewHolder: Creating new ViewHolder.");
            PhotoView pv = new PhotoView(parent.getContext());
            pv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pv.setBackgroundColor(Color.WHITE);
            pv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pv.setAdjustViewBounds(false);

            PdfPageViewHolder holder = new PdfPageViewHolder(pv);

            holder.photoView.getAttacher().setOnMatrixChangeListener(rect -> {
                try {
                    if (holder.isReadyForMatrixUpdates && fragment.isInitialScaleSet) {
                        Log.d(TAG, "OnMatrixChangeListener: Updating shared matrix from position " + holder.getAdapterPosition());
                        holder.photoView.getAttacher().getSuppMatrix(fragment.sharedMatrix);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnMatrixChangeListener: Error", e);
                }
            });

            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull PdfPageViewHolder holder, int position) {
            Log.d(TAG, "onBindViewHolder: Binding view for position " + position);
            holder.isReadyForMatrixUpdates = false;

            if (renderer == null || position < 0 || position >= getItemCount()) {
                return;
            }
            try (PdfRenderer.Page page = renderer.openPage(position)) {
                int viewWidth = holder.itemView.getWidth();
                if (viewWidth == 0) {
                    viewWidth = holder.itemView.getResources().getDisplayMetrics().widthPixels;
                }

                if (viewWidth <= 0) {
                    return;
                }

                float scale = (float) viewWidth / (float) page.getWidth();
                int targetWidth = viewWidth;
                int targetHeight = (int) (page.getHeight() * scale);
                Bitmap bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                holder.photoView.setImageBitmap(bmp);

                final int finalViewWidth = viewWidth;
                holder.photoView.post(() -> {
                    try {
                        PhotoViewAttacher attacher = holder.photoView.getAttacher();
                        attacher.update();

                        if (!fragment.isInitialScaleSet) {
                            Log.d(TAG, "onBindViewHolder post(): Setting INITIAL scale for position " + position);
                            final RectF displayRect = attacher.getDisplayRect();
                            if (displayRect != null && displayRect.width() < finalViewWidth) {
                                final float zoomScale = (float) finalViewWidth / displayRect.width();
                                final float currentScale = attacher.getScale();
                                attacher.setScale(currentScale * zoomScale, 0f, 0f, false);
                            }
                            attacher.getSuppMatrix(fragment.sharedMatrix);
                            fragment.isInitialScaleSet = true;
                            Log.d(TAG, "onBindViewHolder post(): INITIAL scale SET. isInitialScaleSet is now true.");
                        } else {
                            Log.d(TAG, "onBindViewHolder post(): Applying SHARED matrix to position " + position);
                            attacher.setDisplayMatrix(fragment.sharedMatrix);
                        }

                        holder.isReadyForMatrixUpdates = true;
                        Log.d(TAG, "onBindViewHolder post(): View for position " + position + " is now ready for matrix updates.");
                    } catch (Exception e) {
                        Log.e(TAG, "onBindViewHolder post(): Error during setup for position " + position, e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "onBindViewHolder: Error rendering page " + position, e);
            }
        }

        @Override
        public int getItemCount() {
            return renderer.getPageCount();
        }

        static class PdfPageViewHolder extends RecyclerView.ViewHolder {
            final PhotoView photoView;
            boolean isReadyForMatrixUpdates = false;

            PdfPageViewHolder(@NonNull PhotoView pv) {
                super(pv);
                this.photoView = pv;
            }
        }
    }
}

