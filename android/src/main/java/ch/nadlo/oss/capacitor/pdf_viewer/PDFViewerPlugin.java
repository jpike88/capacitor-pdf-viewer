package ch.nadlo.oss.capacitor.pdf_viewer;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "PDFViewer")
public class PDFViewerPlugin extends Plugin {

    private final PDFViewer implementation = new PDFViewer();

    @Override
    public void load() {
        super.load();

        implementation.setBridge(this.getBridge());
    }

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url");
        String title = call.getString("title", "");
        Integer top = call.getInt("top");

        implementation.openViewer(url, title, top);

        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        implementation.close();

        call.resolve();
    }
}
