# Preservar métodos JNI de terminal-emulator y JavascriptInterface de WebView
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class io.deepseek.harness.android.webview.DshWebViewBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
