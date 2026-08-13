package com.wvprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * What does the system WebView tell every site about the app hosting it?
 *
 * The claim that pushed this whole project onto Gecko is that Android's WebView
 * appends X-Requested-With with the embedding package's name, permanently, and
 * that Google refuses sign-in because of it. That was measured on an older
 * build; this re-measures it on whatever provider is installed here.
 */
public class MainActivity extends Activity {
    private static final String TAG = "wvprobe";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "provider: " + WebView.getCurrentWebViewPackage());

        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                // The page is the echo of our own request headers.
                view.evaluateJavascript("document.body.innerText",
                        v -> Log.i(TAG, "HEADERS " + v));
            }
        });
        setContentView(wv);
        wv.loadUrl("https://postman-echo.com/headers");
    }
}
