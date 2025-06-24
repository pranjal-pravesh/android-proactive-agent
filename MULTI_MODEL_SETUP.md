# Multi-Model LLM Setup Guide

This guide explains how to set up and use multiple Language Learning Models (LLMs) in the WhisperNative app, including the new Gemma 3N model with GPU acceleration.

## Available Models

### 1. Qwen 2.5-1.5B Instruct (CPU)
- **Model ID**: `litert-community/Qwen2.5-1.5B-Instruct`
- **Size**: ~1.6GB
- **Features**: Fast, efficient, good for general conversational AI
- **Download**: Automatic from Hugging Face
- **GPU Support**: No (CPU only)

### 2. Gemma 3N 2B Instruct (GPU/CPU)
- **Model ID**: `google/gemma-3n-E2B-it-litert-preview`
- **Size**: ~2.5GB
- **Features**: Higher quality, GPU acceleration support
- **Download**: Manual (requires Google authentication)
- **GPU Support**: Yes (recommended)

## Setup Instructions

### Qwen Model Setup
1. Open the app settings
2. Navigate to "AI Language Models" section
3. Select the "Qwen 2.5-1.5B" tab
4. Click "Download Model" to automatically download from Hugging Face
5. Once downloaded, click "Initialize" to activate the model

### Gemma 3N Model Setup
Since Gemma models require Google authentication, you need to manually download and import the model:

#### Step 1: Download the Model
1. Visit the Google AI model repository
2. Download `gemma-3n-E2B-it-litert-preview.task`
3. Save it to your device's Downloads folder

#### Step 2: Import the Model
1. Open the app settings
2. Navigate to "AI Language Models" section
3. Select the "Gemma 2 2B" tab
4. Click "Auto-find" to automatically locate the downloaded file, or
5. Click "Import .task file" to manually select the file

#### Step 3: Configure GPU Acceleration
1. After importing, the GPU acceleration option will be available
2. Toggle "GPU Acceleration" to enable faster inference
3. Click "Initialize" to activate the model with GPU support

## Model Comparison

| Feature | Qwen 2.5-1.5B | Gemma 3N 2B |
|---------|---------------|-------------|
| Model Size | 1.6GB | 2.5GB |
| Download | Automatic | Manual |
| Speed | Fast (CPU) | Very Fast (GPU) |
| Quality | Good | Excellent |
| GPU Support | No | Yes |
| Setup Difficulty | Easy | Medium |

## GPU Acceleration

### Current Status
GPU acceleration is **planned but not yet implemented** in the current version. The app currently uses:
- **MediaPipe LLM API**: Simpler API but CPU-only
- **Future Implementation**: LiteRT Interpreter API with GPU delegates

### Technical Details
Based on [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) implementation:
- GPU acceleration requires LiteRT Interpreter API with GPU delegates
- Custom tokenization and text generation logic needed
- Compatible with Google's approach in their AI Edge Gallery app

### Device Compatibility
The app can check your device's GPU compatibility:
1. Go to Settings → AI Language Models
2. Select a GPU-capable model (Gemma 3N)
3. View GPU compatibility status in the settings card

### Future Implementation Plan
1. **LiteRT Interpreter Integration**: Replace MediaPipe LLM API for GPU models
2. **Custom Tokenization**: Implement text processing pipeline
3. **GPU Delegate Configuration**: Following Google AI Edge Gallery patterns
4. **Fallback Logic**: Automatic CPU fallback if GPU unavailable

## Performance Optimization

### For Speed (Fast Responses)
```kotlin
val speedConfig = LLMConfig(
    topK = 20,
    topP = 0.8f,
    temperature = 0.5f,
    maxTokens = 512,
    useGPU = true
)
```

### For Quality (Better Responses)
```kotlin
val qualityConfig = LLMConfig(
    topK = 40,
    topP = 0.95f,
    temperature = 0.7f,
    maxTokens = 2048,
    useGPU = true
)
```

### For Balanced Performance
```kotlin
val balancedConfig = LLMConfig(
    topK = 30,
    topP = 0.9f,
    temperature = 0.6f,
    maxTokens = 1024,
    useGPU = true
)
```

## Model Switching

You can switch between models at runtime:
1. Go to app settings
2. Select the desired model tab
3. Click "Initialize" on the new model
4. The app will automatically switch to the new model

## Common File Locations

The app will search for Gemma model files in these locations:
- `/storage/emulated/0/Download/`
- `/storage/emulated/0/Downloads/`
- App's external files directory
- App's cache directory

## Troubleshooting

### Model Not Found
- Ensure the `.task` file is in the Downloads folder
- Check the exact filename: `gemma-3n-E2B-it-litert-preview.task`
- Try using "Import .task file" for manual selection

### Import Failed
- Verify the file is not corrupted
- Check available storage space (need 2.5GB+ free)
- Restart the app and try again

### GPU Initialization Failed
- GPU acceleration will fall back to CPU automatically
- Check device compatibility
- Try disabling and re-enabling GPU acceleration

### Poor Performance
- Check if GPU acceleration is enabled for supported models
- Reduce `maxTokens` for faster responses
- Lower `temperature` for more focused responses
- Consider switching to a faster model configuration

### Gemma Model "Unable to open zip archive" Error

**Problem**: MediaPipe fails to initialize with error "Unable to open zip archive"

**Possible Causes**:
1. **Wrong file format**: You have a .tflite file instead of .task file
2. **Corrupted file**: Download was incomplete or file is damaged
3. **Incompatible format**: File is not MediaPipe-compatible

**Solutions**:

#### Option 1: Verify File Format
Check the error logs for file validation details:
```
LLMManager: First 10 bytes: 50 4b 03 04 ...  // Should start with PK (ZIP header)
```

If the file doesn't start with `50 4b` (PK), it's not a proper .task file.

#### Option 2: Get Correct MediaPipe Format
MediaPipe .task files are specially formatted. You need:
- A `.task` file specifically created for MediaPipe LLM Inference
- Not a raw `.tflite` file
- Not a HuggingFace model file

#### Option 3: Alternative Sources
Try these sources for MediaPipe-compatible Gemma models:
1. Google AI Edge samples repository
2. MediaPipe model garden
3. LiteRT model hub with MediaPipe format

#### Option 4: Convert Existing Model
If you have a .tflite Gemma model:
1. Use MediaPipe Model Maker to convert to .task format
2. Or use the LiteRT model conversion tools
3. Ensure the output is MediaPipe LLM compatible

### File Validation Debug Steps

The app now provides detailed validation:

1. **Check file existence and size**:
   ```
   LLMManager: Model file exists: true
   LLMManager: Model file size: XXXXXXX bytes
   ```

2. **Verify file header**:
   ```
   LLMManager: First 10 bytes: XX XX XX XX...
   ```

3. **ZIP archive validation**:
   ```
   LLMManager: Task file validation passed
   ```

If any of these fail, the file is not in the correct format.

## API Reference

### LLMManager Methods
- `