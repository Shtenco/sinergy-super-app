package com.sinergy.node;

import com.getcapacitor.BridgeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        );

        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            wv.setFocusable(true);
            wv.setFocusableInTouchMode(true);
            wv.getSettings().setDatabaseEnabled(true);
            wv.getSettings().setDomStorageEnabled(true);
        }

        Intent svc = new Intent(this, NodeForegroundService.class);
        startForegroundService(svc);
    }
}
