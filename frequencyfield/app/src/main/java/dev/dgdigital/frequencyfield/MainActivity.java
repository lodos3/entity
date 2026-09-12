package dev.dgdigital.frequencyfield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final String TRUSTED_SCHEME = "https";
    private static final String TRUSTED_HOST = "pemf.vercel.app";
    private static final String APP_URL = "https://pemf.vercel.app";
    private static final long FLUSH_INTERVAL_MS = 16L;
    private static final int MAX_QUEUED_SAMPLES = 512;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final List<MagneticSample> pendingSamples = new ArrayList<>();

    private WebView webView;
    private SensorManager sensorManager;
    private Sensor magneticFieldSensor;
    private boolean pageReady = false;
    private boolean flushScheduled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(3, 4, 10));
        window.setNavigationBarColor(Color.rgb(3, 4, 10));
        window.getDecorView().setSystemUiVisibility(0);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        if (magneticFieldSensor == null) {
            showFatalMessage("This device does not expose an Android magnetic-field sensor.");
            return;
        }

        configureWebView();
        setContentView(webView);
        webView.loadUrl(APP_URL);
    }

    private void configureWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(3, 4, 10));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FrequencyFieldNative/1.0");

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrusted(uri)) {
                    return false;
                }

                Intent external = new Intent(Intent.ACTION_VIEW, uri);
                try {
                    startActivity(external);
                } catch (Exception ignored) {
                    // Keep the WebView on the trusted origin if no external handler exists.
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = isTrusted(Uri.parse(url));
                if (pageReady) {
                    dispatchReady();
                    scheduleFlush();
                }
            }
        });
    }

    private boolean isTrusted(Uri uri) {
        return uri != null
                && TRUSTED_SCHEME.equalsIgnoreCase(uri.getScheme())
                && TRUSTED_HOST.equalsIgnoreCase(uri.getHost());
    }

    private void showFatalMessage(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18f);
        text.setPadding(48, 48, 48, 48);
        text.setBackgroundColor(Color.rgb(3, 4, 10));
        setContentView(text);
    }

    private void dispatchReady() {
        if (!pageReady || webView == null || magneticFieldSensor == null) {
            return;
        }

        JSONObject detail = new JSONObject();
        try {
            detail.put("sensorName", magneticFieldSensor.getName());
            detail.put("vendor", magneticFieldSensor.getVendor());
            detail.put("resolution", magneticFieldSensor.getResolution());
            detail.put("maximumRange", magneticFieldSensor.getMaximumRange());
            detail.put("minDelayUs", magneticFieldSensor.getMinDelay());
        } catch (JSONException ignored) {
            return;
        }

        String script = "window.dispatchEvent(new CustomEvent('native-magnetometer-ready',{detail:"
                + detail.toString() + "}));";
        webView.evaluateJavascript(script, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && magneticFieldSensor != null) {
            sensorManager.registerListener(
                    this,
                    magneticFieldSensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0
            );
        }
        scheduleFlush();
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        mainHandler.removeCallbacks(flushRunnable);
        flushScheduled = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_MAGNETIC_FIELD || event.values.length < 3) {
            return;
        }

        MagneticSample sample = new MagneticSample(
                event.timestamp / 1_000_000.0,
                event.values[0],
                event.values[1],
                event.values[2]
        );

        synchronized (queueLock) {
            if (pendingSamples.size() >= MAX_QUEUED_SAMPLES) {
                pendingSamples.remove(0);
            }
            pendingSamples.add(sample);
        }

        scheduleFlush();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Raw X/Y/Z values are still forwarded. The web UI handles calibration separately.
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            flushSamplesToWeb();
            if (!isFinishing()) {
                scheduleFlush();
            }
        }
    };

    private void flushSamplesToWeb() {
        if (!pageReady || webView == null || magneticFieldSensor == null) {
            return;
        }

        List<MagneticSample> batch;
        synchronized (queueLock) {
            if (pendingSamples.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingSamples);
            pendingSamples.clear();
        }

        JSONArray samples = new JSONArray();
        for (MagneticSample sample : batch) {
            JSONObject value = new JSONObject();
            try {
                value.put("t", sample.timestampMs);
                value.put("x", sample.x);
                value.put("y", sample.y);
                value.put("z", sample.z);
                samples.put(value);
            } catch (JSONException ignored) {
                // Numeric sensor values should always serialize; skip only a malformed entry.
            }
        }

        JSONObject detail = new JSONObject();
        try {
            detail.put("sensorName", magneticFieldSensor.getName());
            detail.put("samples", samples);
        } catch (JSONException ignored) {
            return;
        }

        String script = "window.dispatchEvent(new CustomEvent('native-magnetometer-batch',{detail:"
                + detail.toString() + "}));";
        webView.evaluateJavascript(script, null);
    }

    private static final class MagneticSample {
        final double timestampMs;
        final float x;
        final float y;
        final float z;

        MagneticSample(double timestampMs, float x, float y, float z) {
            this.timestampMs = timestampMs;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
