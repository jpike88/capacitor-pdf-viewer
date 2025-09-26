package ch.nadlo.oss.capacitor.pdf_viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    public ViewPager2 viewPager; // Made public for adapter access
    private ProgressBar progress;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private File tempFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String url;
    private int topMargin;

    public final Matrix sharedMatrix = new Matrix(); // Made public for adapter access
    public boolean isInitialScaleSet = false; // Made public for adapter access
    public boolean isProgrammaticUpdate = false; // Made public for adapter access
    private ViewPager2.OnPageChangeCallback pageChangeCallback;

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

        if (topMargin > 0) {
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
                    pdfFile = url.startsWith("file://") ? new File(Uri.parse(url).getPath()) : new File(url);
                }

                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(fileDescriptor);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        viewPager.setAdapter(new PdfPagerAdapter(pdfRenderer, this));
                        viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);

                        this.pageChangeCallback = new ViewPager2.OnPageChangeCallback() {
                            @Override
                            public void onPageSelected(int position) {
                                super.onPageSelected(position);
                                RecyclerView.ViewHolder holder = ((RecyclerView) viewPager.getChildAt(0)).findViewHolderForAdapterPosition(position);
                                if (holder instanceof PdfPagerAdapter.PdfPageViewHolder) {
                                    PhotoViewAttacher attacher = ((PdfPagerAdapter.PdfPageViewHolder) holder).photoView.getAttacher();
                                    if (attacher != null && isInitialScaleSet) {
                                        isProgrammaticUpdate = true;
                                        attacher.setDisplayMatrix(sharedMatrix);
                                        new Handler(Looper.getMainLooper()).post(() -> isProgrammaticUpdate = false);
                                    }
                                }
                            }
                        };
                        viewPager.registerOnPageChangeCallback(this.pageChangeCallback);
                        progress.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadPdf: Failed to load or render PDF.", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> getParentFragmentManager().beginTransaction().remove(this).commit());
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (viewPager != null && pageChangeCallback != null) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback);
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
            if (tempFile != null && tempFile.exists()) tempFile.delete();
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
            while ((n = in.read(buf)) >= 0) outStream.write(buf, 0, n);
        }
        return out;
    }

    private static File copyContentUriToCache(Context ctx, Uri uri) throws Exception {
        File out = File.createTempFile("pdf_viewer_", ".pdf", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream outStream = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) outStream.write(buf, 0, n);
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
            PhotoView pv = new PhotoView(parent.getContext());
            pv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pv.setBackgroundColor(Color.WHITE);
            pv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pv.setAdjustViewBounds(false);
            PdfPageViewHolder holder = new PdfPageViewHolder(pv);

            holder.photoView.getAttacher().setOnMatrixChangeListener(rect -> {
                if (holder.getAdapterPosition() != fragment.viewPager.getCurrentItem()) {
                    return;
                }
                if (fragment.isProgrammaticUpdate) return;

                try {
                    if (holder.getAdapterPosition() != RecyclerView.NO_POSITION && holder.isReadyForMatrixUpdates && fragment.isInitialScaleSet) {
                        // FIX: Pass the matrix to be filled
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
            holder.isReadyForMatrixUpdates = false;
            if (renderer == null || position < 0 || position >= getItemCount()) return;

            try (PdfRenderer.Page page = renderer.openPage(position)) {
                int viewWidth = holder.itemView.getWidth();
                if (viewWidth == 0) viewWidth = holder.itemView.getResources().getDisplayMetrics().widthPixels;
                if (viewWidth <= 0) return;

                float scale = (float) viewWidth / (float) page.getWidth();
                Bitmap bmp = Bitmap.createBitmap(viewWidth, (int) (page.getHeight() * scale), Bitmap.Config.ARGB_8888);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                holder.photoView.setImageBitmap(bmp);

                int finalViewWidth = viewWidth;
                holder.photoView.post(() -> {
                    try {
                        PhotoViewAttacher attacher = holder.photoView.getAttacher();
                        attacher.update();

                        if (!fragment.isInitialScaleSet) {
                            final RectF displayRect = attacher.getDisplayRect();
                            if (displayRect != null && displayRect.width() < finalViewWidth) {
                                attacher.setScale(attacher.getScale() * (finalViewWidth / displayRect.width()), 0f, 0f, false);
                            }
                            // FIX: Pass the matrix to be filled
                            attacher.getSuppMatrix(fragment.sharedMatrix);
                            fragment.isInitialScaleSet = true;
                        } else {
                            attacher.setDisplayMatrix(fragment.sharedMatrix);
                        }
                        holder.isReadyForMatrixUpdates = true;
                    } catch (Exception e) {
                        Log.e(TAG, "onBindViewHolder post(): Error", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "onBindViewHolder: Error rendering page", e);
            }
        }

        @Override
        public int getItemCount() { return renderer.getPageCount(); }

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
