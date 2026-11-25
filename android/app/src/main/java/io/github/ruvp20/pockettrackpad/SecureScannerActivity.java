package io.github.ruvp20.pockettrackpad;

import android.os.Bundle;
import android.view.WindowManager;
import com.journeyapps.barcodescanner.CaptureActivity;

/** Applies the same capture policy to the QR camera window as the touch surface. */
public final class SecureScannerActivity extends CaptureActivity {
    @Override protected void onCreate(Bundle state) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(state);
    }
}
