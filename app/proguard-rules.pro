# Keep JavascriptInterface bridge used by WebView blob downloads
-keepclassmembers class com.rg.webloom.data.DownloadHelper$BlobBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Narrowed: only the overrides we actually use (broad public-* keeps defeat R8 shrinking).
-keepclassmembers class * extends android.webkit.WebViewClient {
    public boolean shouldOverrideUrlLoading(...);
    public void onPageStarted(...);
    public void onPageFinished(...);
    public void doUpdateVisitedHistory(...);
    public android.webkit.WebResourceResponse shouldInterceptRequest(...);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void onProgressChanged(...);
    public boolean onCreateWindow(...);
    public boolean onConsoleMessage(...);
}
-dontwarn android.webkit.WebView
-dontwarn android.webkit.WebViewClient
-dontwarn android.webkit.WebChromeClient
# shrink (kept conservative: repackage/allowaccessmodification break ViewBinding + FileProvider)
-optimizationpasses 5
