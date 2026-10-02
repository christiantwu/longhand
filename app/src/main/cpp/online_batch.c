// Batched decoding for sherpa-onnx's online (streaming) recognizer, which Hindi uses (see engine/StreamingRecognizer.kt).
// libsherpa-onnx-jni.so implements OnlineRecognizer.decodeStreams, but the Kotlin class in the 1.13.8 AAR doesn't declare
// it, and a JNI function can only be reached through the class that declares it. This looks the function up and calls it.
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

typedef void (*DecodeStreams)(JNIEnv *, jobject, jlong, jlongArray);

static pthread_once_t looked_up = PTHREAD_ONCE_INIT;
static DecodeStreams decode_streams = NULL;

static void look_up(void) {
    // Already loaded with the recognizer's class, so this only finds it.
    void * lib = dlopen("libsherpa-onnx-jni.so", RTLD_NOW);
    if (lib) decode_streams = (DecodeStreams) dlsym(lib, "Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_decodeStreams");
}

// One decoding step for each of the streams (their native pointers), together. Returns false, having decoded nothing,
// when sherpa-onnx lacks the function or the recognizer's pointer field.
JNIEXPORT jboolean JNICALL
Java_io_github_christiantwu_longhand_engine_OnlineBatch_nativeDecode(JNIEnv * env, jclass cls, jobject recognizer, jlongArray streams) {
    (void) cls;
    pthread_once(&looked_up, look_up);
    if (!decode_streams) return JNI_FALSE;
    // The Kotlin class keeps the native recognizer in a private field.
    jfieldID ptr = (*env)->GetFieldID(env, (*env)->GetObjectClass(env, recognizer), "ptr", "J");
    if (!ptr) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }
    // sherpa-onnx doesn't use the object it's called on, only the recognizer and stream pointers.
    decode_streams(env, recognizer, (*env)->GetLongField(env, recognizer, ptr), streams);
    return JNI_TRUE;
}
