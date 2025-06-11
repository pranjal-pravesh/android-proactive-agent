#include <jni.h>
#include "TFLiteEngine.h"

extern "C" {

// JNI method to create an instance of TFLiteEngine
JNIEXPORT jlong JNICALL
Java_com_proactiveagentv2_engine_WhisperEngineNative_createTFLiteEngine(JNIEnv *env, jobject thiz) {
    TFLiteEngine *engine = new TFLiteEngine();
    return reinterpret_cast<jlong>(engine);
}

// JNI method to load the model
JNIEXPORT jint JNICALL
Java_com_proactiveagentv2_engine_WhisperEngineNative_loadModel(JNIEnv *env, jobject thiz, jlong nativePtr, jstring modelPath, jboolean isMultilingual) {
    TFLiteEngine *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    const char *modelPathStr = env->GetStringUTFChars(modelPath, nullptr);
    int result = engine->loadModel(modelPathStr, static_cast<bool>(isMultilingual));
    env->ReleaseStringUTFChars(modelPath, modelPathStr);
    return result;
}

// JNI method to free the model
JNIEXPORT void JNICALL
Java_com_proactiveagentv2_engine_WhisperEngineNative_freeModel(JNIEnv *env, jobject thiz, jlong nativePtr) {
    TFLiteEngine *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    engine->freeModel();
    delete engine;
}

// JNI method to transcribe audio buffer
JNIEXPORT jstring JNICALL
Java_com_proactiveagentv2_engine_WhisperEngineNative_transcribeBuffer(JNIEnv *env, jobject thiz, jlong nativePtr, jfloatArray samples) {
    TFLiteEngine *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    
    // Convert jfloatArray to std::vector<float>
    jsize length = env->GetArrayLength(samples);
    jfloat *samplesPtr = env->GetFloatArrayElements(samples, nullptr);
    std::vector<float> sampleVector(samplesPtr, samplesPtr + length);
    env->ReleaseFloatArrayElements(samples, samplesPtr, JNI_ABORT);
    
    std::string result = engine->transcribeBuffer(sampleVector);
    return env->NewStringUTF(result.c_str());
}

// JNI method to transcribe audio file
JNIEXPORT jstring JNICALL
Java_com_proactiveagentv2_engine_WhisperEngineNative_transcribeFile(JNIEnv *env, jobject thiz, jlong nativePtr, jstring waveFile) {
    TFLiteEngine *engine = reinterpret_cast<TFLiteEngine *>(nativePtr);
    const char *waveFileStr = env->GetStringUTFChars(waveFile, nullptr);
    std::string result = engine->transcribeFile(waveFileStr);
    env->ReleaseStringUTFChars(waveFile, waveFileStr);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
