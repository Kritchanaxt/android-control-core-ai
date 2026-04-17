
# android-control-core-ai

**(Layer 1: Core System / Infrastructure + Layer 2: Hybrid AI & OCR Capabilities)**

Android system-level control core featuring a persistent background service, **Hybrid AI-powered OCR scanner**, overlay process, WebSocket command bus, and structured logging.

---

## 🌟 Key Features

### **Hybrid AI & OCR Scanning (PP-OCRv5)**

* **Advanced Hybrid Architecture**: Combining the speed of **NCNN** (for DBNet Detection) with the precision of **ONNX Runtime C++ API** (for SVTR Recognition). This hybrid approach resolves complex 3D MatMul issues in SVTR layers while maintaining maximum performance.
* **PP-OCRv5 Support**: Native support for **PaddleOCR v5** models (Thai & English), capable of reading complex document layouts, Thai ID cards, and receipts with high accuracy.
* **Zero-Copy Inference**: Direct memory mapping of `.onnx` models via AssetManager to C++ execution runtime, bypassing Java overhead entirely.
* **Optimized Performance**: 
  - **Detection**: NCNN (Vulkan disabled for stability/CPU threading force) with optimized `target_size=320` for balance between speed and small-text accuracy.
  - **Recognition**: ONNX Runtime 1.17.1 (Standard C++ API with Exceptions enabled).
  - **Multi-Core Scaling**: Explicitly tuned for **6-Core CPU execution** to minimize latency during high-density text scanning.
  - **Reduced Footprint**: Legacy NCNN recognition models (`.param`/`.bin`) removed, relying solely on efficient `.onnx` models (~14MB total model size).
  - **Speed**: Full OCR pipeline runs in **~100-200ms** on mid-range Android devices.
* **Crash-Free Stability**: Solved critical JNI crashes by overriding NCNN's default `-fno-exceptions` flags, enabling robust error handling in the C++ layer.

* **Smart Auto-Crop (Tight Bounding Box)**: 
  - **Dynamic Ratio Cropping**: Intelligently calculates crop boundaries by anchoring to specific string positions (e.g., cutting the top 45% of the ID number box to avoid overlapping headers like "บัตรประจำตัวประชาชน").
  - **Keyword Filtering**: Ignores generic labels ("เลขประจำตัว", "ชื่อตัวและชื่อสกุล") and locks strictly onto the 13-digit ID and Thai name prefixes ("นาย", "นาง", etc.) for a millimeter-perfect crop.
* **Low-RAM Device Optimization (2GB RAM Safe)**:
  - **Pre-Capture Resolution Selection**: Allows users to select lower camera resolutions before taking a snap, preventing OOM (Out of Memory) crashes during full-res inference.
  - **Fast Preview Downscaling**: Shrinks the live camera preview to 640px for real-time text detection without overloading the memory.
  - **Memory Safeguards**: Forces aggressive garbage collection (`Bitmap.recycle()`) and enables OS-level `android:largeHeap="true"` to prevent app termination on budget devices.
  - **Lenient Snap Triggers**: Triggers the auto-snap smoothly even if the camera only catches a partial ID (9+ digits) or keywords ("ประชาชน"), making it extremely user-friendly and easy to scan.
* **Automated Thai Text Formatting**: 
  - Uses Regex string manipulation to automatically inject properly formatted spaces between Thai titles and names (e.g., `นายกฤชณัช` -> `นาย กฤชณัช`).
* **Streamlined UI**: Removed unnecessary debugging info (Inference time, Preview mode, Raw data) and added a clean `✅ Snap success!` visual state.



### **Multi-Mode AI System**
* **Dynamic AI Mode Selector**: Easily toggle between distinct AI processors directly from the UI dropdown (e.g., `OCR` and `PALMPRINT`). 
* **MediaPipe Hand Landmarker**: Integrates Google's MediaPipe for precise 3D hand tracking and palmprint feature extraction.
  - Efficiently detects hand coordinates and isolates the region of interest (ROI) for Palmprint detection.
  - Scales fluidly alongside the existing OCR pipeline, allowing the same unified Camera/Preview engine to power completely different tasks.
  - Defers model initialization until process execution to conserve memory on low-RAM devices until the mode is actively selected.

### **WebSocket Communication (JSON-First)**

* **Server Mode**: Runs an internal WebSocket server inside the Android app (Port `8887`) for direct client connections.
* **JSON Protocol**: Uses JSON as the primary communication format between the Android app and the web client—lightweight, readable, and easy to debug.
* **Real-Time Control**: Supports real-time two-way commands from the web client (Ping, Notification, Authentication) with immediate responses.

### **Web Client Interface**

* Includes a ready-to-use web interface (`web_client/index.html`) for testing connections and sending commands.
* **Auto-Reconnect**: Automatically retries connection when disconnected.
* **Live Log Viewer**: View real-time logs and responses from the Android device directly in the browser.

### **Background Operation & Reliability 🛡️**

* **Heartbeat System**: Continuously sends status signals to the web client to confirm the app is alive—even when running in the background.
* **Smart State Tracking**: Uses `ProcessLifecycleOwner` & `BroadcastReceiver` to detect and broadcast real-time states (`SCREEN_OFF`, `BACKGROUND`, `FOREGROUND`) to connected clients via WebSocket.
* **Watchdog (Self-Healing)**: Implements `androidx.work.WorkManager` to wake up every 15 minutes, check service health, and perform **auto-revival** if the OS kills the process.
* **Foreground Service**: Ensures long-running execution without being killed by the system.
* **Auto-Start on Boot**: Automatically starts the service when the device boots (via Boot Receiver).


### **Remote Monitoring (Cloud Telemetry) ☁️**
* **Firebase Firestore Logging (`FirebaseLogger`)**: 
  - Streams real-time diagnostics directly to Google Cloud Firestore.
  - Automatically attaches vital device health metadata to every log entry, including:
    - **Memory Metrics**: Current available RAM, low-memory warnings, and total system RAM capacity.
    - **Battery & Power State**: Real-time battery percentage, charging status, and device thermal state (e.g., interactive/idle).
    - **App Resilience**: Tracks `BACKGROUND`, `FOREGROUND`, and `SCREEN_ON`/`OFF` cycles.
  - Useful for debugging fleets of devices remotely, ensuring that app crashes (like OOM events) or device failures can be traced back to exact system constraints prior to failure.
* **Persistent Cloud Audit**: Provides historical, remote-accessible logs beyond local WebSocket boundaries, making it robust for field deployments.

### **Logging & Export**

* **Structured Logs**: Logs include timestamps, components, events, and payload data.
* **JSON Export**: Logs can be exported as JSON files for offline analysis (local time aligned with Thailand timezone).

### **Security**

* **Passkey Authentication**: Requires a valid passkey before accepting any remote command.

### **Performance & Resource Monitoring**

* **Real-Time Metrics**: Continuously sends RAM Usage (app/system), Battery Level, and Device Manufacturer info to the connected WebSocket client.

---

## 🛠️ Technical Stack & Implementation

### **Native C++ Layer (`app/src/main/cpp`)**
* **`paddleocr_ncnn.cpp`**: The core JNI bridge.
  - **NCNN**: Handles text detection (DBNet) efficiently.
  - **ONNX Runtime (ORT)**: Handles text recognition (CRNN/SVTR) to support advanced operational layers that NCNN cannot compute correctly (e.g., 3D MatMul in SVTR).
* **CMake Configuration**:
  - Custom flag override: `set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")` to enable standard C++ features required by ORT.
  - Dynamic linking of `libonnxruntime.so` and `libncnn.a`.

### **Android Architecture**
* **Language**: Kotlin (First-class support) + Java Native Interface (JNI).
* **Concurrency**: Coroutines for non-blocking I/O operations (WebSocket, Database).
* **Scanning**: CameraX / Camera2 API with custom resolution strategies.

---

## 🚀 Getting Started

### Prerequisites

* Android Studio Hedgehog or newer.
* Android Device/Emulator (Minimum SDK 24, Target SDK 34).
* NDK (Side switch enabled in `local.properties` if needed).

### Installation

1. Clone the repository.
2. Open in Android Studio.
3. Sync Gradle (Ensure CMake 3.22.1+ and NDK are installed).
4. Run on device.
