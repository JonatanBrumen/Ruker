#include <jni.h>
#include <string>
#include <vector>
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_ruker_MainActivity_classify(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray input_features) {

    jfloat* features = env->GetFloatArrayElements(input_features, 0);
    jsize count = env->GetArrayLength(input_features);

    signal_t signal;
    numpy::signal_from_buffer(features, count, &signal);

    ei_impulse_result_t result = { 0 };
    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);

    int output_count = EI_CLASSIFIER_LABEL_COUNT + (EI_CLASSIFIER_HAS_ANOMALY ? 1 : 0);
    jfloatArray outArray = env->NewFloatArray(output_count);
    std::vector<float> out_data;

    for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
        out_data.push_back(result.classification[ix].value);
    }
    if (EI_CLASSIFIER_HAS_ANOMALY) {
        out_data.push_back(result.anomaly);
    }

    env->SetFloatArrayRegion(outArray, 0, output_count, out_data.data());
    env->ReleaseFloatArrayElements(input_features, features, 0);

    return outArray;
}
