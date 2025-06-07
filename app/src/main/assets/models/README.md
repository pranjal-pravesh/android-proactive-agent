# Whisper Models Directory

This directory should contain the whisper.cpp GGML model files for speech transcription.

## Required Model

Place your whisper model file here with the name: `ggml-tiny.bin`

## Downloading Models

You can download pre-converted GGML models from:
- [Hugging Face whisper.cpp models](https://huggingface.co/ggerganov/whisper.cpp)
- [Official whisper.cpp repository](https://github.com/ggerganov/whisper.cpp/tree/master/models)

## Recommended Models

For mobile devices, we recommend:
- **ggml-tiny.bin** (~39MB) - Fastest, good for real-time transcription
- **ggml-base.bin** (~142MB) - Better accuracy, still fast
- **ggml-small.bin** (~466MB) - High accuracy, slower

## Model Conversion

If you need to convert models yourself:
1. Download the original OpenAI whisper model
2. Use the conversion script from whisper.cpp:
   ```bash
   python convert-pt-to-ggml.py path/to/whisper/model
   ```

## File Structure

```
app/src/main/assets/models/
├── README.md (this file)
└── ggml-tiny.bin (your whisper model)
```

## Note

The app will automatically copy the model from assets to internal storage on first run.
Make sure your model file is named exactly `ggml-tiny.bin` or update the model path in `LiveTranscriptionManager.kt`. 