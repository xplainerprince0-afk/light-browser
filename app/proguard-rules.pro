# Keep JavascriptInterface bridge used by WebView blob downloads
-keepclassmembers class com.lightbrowser.data.DownloadHelper$BlobBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.lightbrowser.data.DownloadHelper$BlobBridge { *; }
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }
-dontwarn android.webkit.**
# shrink (kept conservative: repackage/allowaccessmodification break ViewBinding + FileProvider)
-optimizationpasses 5
