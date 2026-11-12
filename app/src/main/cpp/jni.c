#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include "ocbridge.h"

static JavaVM *vm;
typedef struct {
    ocb_session *s;
    jobject callbacks;
    jmethodID log, event, trust, protect;
    char error[512];
} bridge;

// Native worker/timer threads may not belong to the JVM. Each callback owns a
// local frame and detaches only when it attached the thread itself.
typedef struct { JNIEnv *env; int attached; } callback_env;
static callback_env enter(void) {
    callback_env c = {0};
    if ((*vm)->GetEnv(vm, (void **)&c.env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &c.env, NULL) != JNI_OK) { c.env = NULL; return c; }
        c.attached = 1;
    }
    if (c.env && (*c.env)->PushLocalFrame(c.env, 64) < 0) {
        (*c.env)->ExceptionClear(c.env);
        if (c.attached) (*vm)->DetachCurrentThread(vm);
        c.env = NULL;
    }
    return c;
}
static int leave(callback_env c) {
    if (!c.env) return 0;
    int ok = !(*c.env)->ExceptionCheck(c.env);
    // Fail closed for certificate/protect exceptions; don't let Java exceptions
    // escape into OpenConnect's C event loop.
    if (!ok) (*c.env)->ExceptionClear(c.env);
    (*c.env)->PopLocalFrame(c.env, NULL);
    if (c.attached) (*vm)->DetachCurrentThread(vm);
    return ok;
}
static jstring string(JNIEnv *e, const char *s) { return (*e)->NewStringUTF(e, s ? s : ""); }
static jobjectArray strings(JNIEnv *e, const char **s, int n) {
    jclass cls = (*e)->FindClass(e, "java/lang/String");
    jobjectArray a = (*e)->NewObjectArray(e, n, cls, NULL);
    (*e)->DeleteLocalRef(e, cls);
    for (int i = 0; i < n; i++) {
        jstring v = string(e, s[i]);
        (*e)->SetObjectArrayElement(e, a, i, v);
        (*e)->DeleteLocalRef(e, v);
    }
    return a;
}
static jobjectArray config(JNIEnv *e, const ocb_config *c) {
    char mtu[20]; snprintf(mtu, sizeof mtu, "%d", c->mtu);
    const char *parts[] = {c->addr, c->netmask, c->addr6, c->dns[0], c->dns[1], c->dns[2],
        c->domain, c->gateway, c->split_includes, c->split_excludes, c->split_dns, mtu, c->key};
    return strings(e, parts, 13);
}
static void log_cb(void *ctx, int level, const char *line) {
    (void)level; bridge *b = ctx; callback_env c = enter(); if (!c.env) return;
    (*c.env)->CallVoidMethod(c.env, b->callbacks, b->log, string(c.env, line));
    leave(c);
}
static void event_cb(void *ctx, int event, const ocb_config *cfg) {
    bridge *b = ctx; callback_env c = enter(); if (!c.env) return;
    (*c.env)->CallVoidMethod(c.env, b->callbacks, b->event, event, cfg ? config(c.env, cfg) : NULL);
    leave(c);
}
static int trust_cb(void *ctx, const unsigned char **der, const int *len, int n, const char *host) {
    bridge *b = ctx; callback_env c = enter(); if (!c.env) return 0;
    JNIEnv *e = c.env;
    jclass cls = (*e)->FindClass(e, "[B");
    jobjectArray chain = (*e)->NewObjectArray(e, n, cls, NULL);
    for (int i = 0; i < n; i++) {
        jbyteArray bytes = (*e)->NewByteArray(e, len[i]);
        (*e)->SetByteArrayRegion(e, bytes, 0, len[i], (const jbyte *)der[i]);
        (*e)->SetObjectArrayElement(e, chain, i, bytes);
        (*e)->DeleteLocalRef(e, bytes);
    }
    jboolean ok = (*e)->CallBooleanMethod(e, b->callbacks, b->trust, chain, string(e, host));
    return leave(c) && ok;
}
static void poison_socket(int fd) {
    int inert = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (inert >= 0) { (void)dup2(inert, fd); if (inert != fd) close(inert); }
    else close(fd);
}
static void protect_cb(void *ctx, int fd) {
    bridge *b = ctx; callback_env c = enter();
    if (!c.env) { poison_socket(fd); return; }
    jboolean ok = (*c.env)->CallBooleanMethod(c.env, b->callbacks, b->protect, fd);
    // The API returns void. Replace an unprotected socket with an inert fd:
    // connect/send fails, and OpenConnect closes it without an fd reuse race.
    if (!leave(c) || !ok) poison_socket(fd);
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *v, void *unused) {
    (void)unused; vm = v; return JNI_VERSION_1_6;
}
#define API(name) Java_com_muunkk_connectanytime_NativeBridge_##name
#define B ((bridge *)(intptr_t)handle)
JNIEXPORT void JNICALL API(setNetwork)(JNIEnv *e, jobject self, jlong handle) {
    (void)e; (void)self; ocb_set_network((uint64_t)handle);
}
JNIEXPORT jlong JNICALL API(create)(JNIEnv *e, jobject self, jstring server, jstring user, jstring password, jobject cb) {
    (void)self; bridge *b = calloc(1, sizeof *b); if (!b) return 0;
    b->callbacks = (*e)->NewGlobalRef(e, cb);
    jclass cls = (*e)->GetObjectClass(e, cb);
    b->log = (*e)->GetMethodID(e, cls, "onLog", "(Ljava/lang/String;)V");
    b->event = (*e)->GetMethodID(e, cls, "onEvent", "(I[Ljava/lang/String;)V");
    b->trust = (*e)->GetMethodID(e, cls, "verify", "([[BLjava/lang/String;)Z");
    b->protect = (*e)->GetMethodID(e, cls, "protectSocket", "(I)Z");
    (*e)->DeleteLocalRef(e, cls);
    const char *s = (*e)->GetStringUTFChars(e, server, NULL);
    const char *u = (*e)->GetStringUTFChars(e, user, NULL);
    const char *p = (*e)->GetStringUTFChars(e, password, NULL);
    b->s = ocb_new(s, u, p, "push", log_cb, event_cb, trust_cb, protect_cb, b);
    (*e)->ReleaseStringUTFChars(e, server, s);
    (*e)->ReleaseStringUTFChars(e, user, u);
    (*e)->ReleaseStringUTFChars(e, password, p);
    if (!b->s) { (*e)->DeleteGlobalRef(e, b->callbacks); free(b); return 0; }
    return (jlong)(intptr_t)b;
}
JNIEXPORT jint JNICALL API(connect)(JNIEnv *e, jobject self, jlong handle) {
    (void)e; (void)self; return ocb_connect(B->s, B->error, sizeof B->error);
}
JNIEXPORT jint JNICALL API(resume)(JNIEnv *e, jobject self, jlong handle, jobjectArray saved) {
    (void)self; const char *s[4]; jstring js[4];
    for (int i = 0; i < 4; i++) { js[i] = (*e)->GetObjectArrayElement(e, saved, i); s[i] = (*e)->GetStringUTFChars(e, js[i], NULL); }
    int r = ocb_resume(B->s, s[0], s[1], s[2], s[3], B->error, sizeof B->error);
    for (int i = 0; i < 4; i++) { (*e)->ReleaseStringUTFChars(e, js[i], s[i]); (*e)->DeleteLocalRef(e, js[i]); }
    return r;
}
JNIEXPORT jstring JNICALL API(error)(JNIEnv *e, jobject self, jlong handle) {
    (void)self; return string(e, B->error);
}
JNIEXPORT jobjectArray JNICALL API(saved)(JNIEnv *e, jobject self, jlong handle) {
    (void)self; const char *s[] = {ocb_session_url(B->s), ocb_session_cookie(B->s), ocb_session_dnsname(B->s), ocb_session_ip(B->s)};
    return strings(e, s, 4);
}
JNIEXPORT jobjectArray JNICALL API(config)(JNIEnv *e, jobject self, jlong handle) {
    (void)self; ocb_config cfg; if (ocb_get_config(B->s, &cfg)) return NULL;
    jobjectArray a = config(e, &cfg); ocb_config_free(&cfg); return a;
}
JNIEXPORT jint JNICALL API(run)(JNIEnv *e, jobject self, jlong handle, jint fd) {
    (void)e; (void)self; return ocb_run(B->s, fd, 1800);
}
JNIEXPORT jboolean JNICALL API(abortBeforePassword)(JNIEnv *e, jobject self, jlong handle) {
    (void)e; (void)self; return ocb_cancel_unless_answered(B->s);
}
JNIEXPORT void JNICALL API(command)(JNIEnv *e, jobject self, jlong handle, jint cmd) {
    (void)e; (void)self;
    if (cmd == 0) ocb_reconnect(B->s);
    else if (cmd == 1) ocb_cancel(B->s);
    else ocb_detach(B->s);
}
JNIEXPORT void JNICALL API(free)(JNIEnv *e, jobject self, jlong handle) {
    (void)self; ocb_free(B->s); (*e)->DeleteGlobalRef(e, B->callbacks); free(B);
}
