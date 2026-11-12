# jni.c exports Java_com_muunkk_connectanytime_NativeBridge_* entry points.
-keep,allowoptimization class com.muunkk.connectanytime.NativeBridge {
    native <methods>;
}
# GetObjectClass + GetMethodID on the concrete callback, with these exact descriptors.
-keep interface com.muunkk.connectanytime.NativeBridge$Callbacks {
    void onLog(java.lang.String);
    void onEvent(int, java.lang.String[]);
    boolean verify(byte[][], java.lang.String);
    boolean protectSocket(int);
}
-keepclassmembers class * implements com.muunkk.connectanytime.NativeBridge$Callbacks {
    void onLog(java.lang.String);
    void onEvent(int, java.lang.String[]);
    boolean verify(byte[][], java.lang.String);
    boolean protectSocket(int);
}
# FindClass only uses java/lang/String and [B (platform types); no GetFieldID,
# app class lookup or app constructors occur in jni.c. No further keeps needed.
