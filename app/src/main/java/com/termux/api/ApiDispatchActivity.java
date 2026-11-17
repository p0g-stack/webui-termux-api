package com.termux.api;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import com.termux.api.util.ResultReturner;

/** Root-only entrypoint for APIs that need while-in-use permission eligibility. */
public final class ApiDispatchActivity extends Activity {
    private Request request;

    private static final class Request extends ResultReceiver {
        Activity activity;
        boolean started;
        boolean complete;

        Request() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            complete = true;
            if (activity != null) activity.finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        request = (Request) getLastNonConfigurationInstance();
        if (request == null) {
            request = new Request();
            if (savedInstanceState != null && savedInstanceState.getBoolean("started")) {
                // A killed process cannot resume a hardware operation. Never replay it.
                request.started = true;
                ResultReturner.returnData(
                        this,
                        getIntent(),
                        out -> out.println("ERROR: foreground API request interrupted"));
            }
        }
        request.activity = this;
        getIntent().putExtra(ResultReturner.COMPLETION_EXTRA, request);
        if (request.complete) finish();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return request;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("started", request.started);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || request.started) return;
        request.started = true;
        String method = getIntent().getStringExtra("api_method");
        if (!"CameraPhoto".equals(method)
                && !"MicRecorder".equals(method)
                && !"Location".equals(method)
                && !"SpeechToText".equals(method)
                && !"Sensor".equals(method)) {
            ResultReturner.returnData(
                    this,
                    getIntent(),
                    out -> out.println("ERROR: unsupported foreground API: " + method));
            return;
        }
        try {
            RootHelperService.prepareApi(this, method);
            new TermuxApiReceiver().onReceive(this, getIntent());
        } catch (RuntimeException e) {
            ResultReturner.returnData(
                    this, getIntent(), out -> out.println("ERROR: " + e.getMessage()));
        }
        // ResultReturner closes the socket before finishing us. Pausing is not
        // cancellation: dialogs, rotation and background work must keep running.
    }

    @Override
    protected void onDestroy() {
        if (request.activity == this) request.activity = null;
        super.onDestroy();
    }
}
