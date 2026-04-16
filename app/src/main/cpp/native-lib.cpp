#include <jni.h>
#include <string>
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ruker_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Edge Impulse Loaded!";
    return env->NewStringUTF(hello.c_str());
}