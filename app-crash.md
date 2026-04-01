จากข้อมูลของโปรเจกต์ android-control-ocr และปัญหาที่พี่หมี (P'Bear) แจ้งมาว่า "แอป crash และใช้งานไม่ได้ตอนกดไปมาจาก feature ต่างๆ" โดยมีคำแนะนำให้ "setup project ให้ main นิ่งๆ ก่อน"

อาการแอปเด้งเวลาเปลี่ยนหน้า (Navigate ไปมา) ในแอปที่มีการใช้ Camera และ AI Models (NCNN + ONNX) มักจะเกิดจากปัญหา Resource Management (การจัดการทรัพยากร) ครับ

เพื่อที่จะทำให้ "main นิ่ง" ตามหลักการ เราควรโฟกัสวิเคราะห์และแก้ปัญหาใน 4 จุดหลักๆ ดังนี้ครับ:

### 1. ปัญหาการจัดการ Camera Lifecycle (CameraX / Camera2)
เวลาเปลี่ยนหน้า (Fragment/Activity) หากแอปไม่ได้ปิดหรือ Unbind กล้องอย่างถูกต้อง กล้องค้างอยู่ใน background พอเปิดกลับมาอีกครั้งจะทำให้เกิด Crash ได้

- แนวทางแก้ไข: ตรวจสอบว่าในขั้นตอน onDestroy หรือ onPause ได้ทำการเรียก cameraProvider.unbindAll() เคลียร์ UseCase ต่างๆ อย่างถูกต้องหรือไม่

### 2. ปัญหา Memory Leak จาก Native C++ (JNI / ONNX / NCNN)
โปรเจกต์นี้มีการจองหน่วยความจำในฝั่ง C++ ค่อนข้างเยอะ (โมเดล OCR 14MB, Image Matrices) ถ้าไปมาหลายๆ หน้าแล้วแอปค้างหรือ Crash มักจะเกิดจาก Out of Memory (OOM) หรือ Native Crash (SIGSEGV)

- แนวทางแก้ไข: ตรวจสอบไฟล์ paddleocr_ncnn.cpp หรือคลาสที่จัดการ JNI ว่าเมื่อเปลี่ยนหน้าแล้ว มีการเรียกฟังก์ชันทำลาย (Destroy/Release) โมเดล NCNN/ONNX หรือเคลียร์ตัวแปรระดับ Global/Pointer หรือไม่ ถ้าไม่มีการ delete หรือล้างหน่วยความจำ จะทำให้เกิด Memory Leak สะสมจนแอปพัง

### 3. ปัญหา Coroutines & WebSocket ที่ไม่ยอมตาย
มี WebSocket Server (Port 8887) และ Background Service ทำงานอยู่ หากเปลี่ยนหน้าจอแล้วมีการสร้าง instance ค้างไว้เรื่อยๆ (Multiple Observers, Multiple WebSockets) จะทำให้ thread ชนกัน

- แนวทางแก้ไข: ตรวจสอบว่า View/ViewModel มีการ Cancel Coroutine Job หรือ Unregister BroadcastReceiver เมื่อ View ถูกทำลายหรือไม่

### 4. ปัญหา JNI Local Reference Exhaustion
เวลาประมวลผลกล้องแต่ละเฟรมใน C++ (รัน OCR ทีละ 100-200ms) หากส่งค่ากลับไปมาตลอดยาวๆ โดยไม่ล้าง JNI Local Reference ทิ้ง จะทำให้ Reference ทะลุลิมิต (ปกติประมาณ 512 โควตาต่อ Thread) ของ Android

- แนวทางแก้ไข: ใช้ env->DeleteLocalRef() ในลูปของ C++ ทุกครั้งที่สร้าง Object เสร็จ

### ขั้นตอนที่ผมแนะนำให้เริ่มทำเลย (How to start):
1. ขอดู Crash Log ก่อน: รบกวนเปิด Android Studio -> แท็บ Logcat แล้วจำลองการกดเปลี่ยนหน้าไปมาจนแอป Crash จากนั้นก็อปปี้ Log ตัวสีแดง (Error) หรือส่วนที่เป็น FATAL EXCEPTION หรือ SIGSEGV มาให้ผมดูครับ
2. ติดเครื่องมือช่วยหาจุดรั่ว (LeakCanary): ถ้าคิดว่าเป็น OOM หรือ Memory Leak ให้เพิ่ม debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.13' ใน build.gradle.kts เพื่อหาว่า Object ไหนค้างอยู่ในระบบเวลาเราสลับหน้าจอ

ปัญหาหลักคือ Thread Concurrency (การชนกันของ Thread) และ Memory Leaks ระหว่างที่เปลี่ยนไปมาในหน้าจอแอป (หน้า OCRScreen) ทำให้มีการโหลดโมเดลทับซ้อนกันขณะที่การสแกน detectNative ยังรันเครือข่าย NCNN / ONNX ไม่เสร็จ โมเดลถูกสั่งล้างหรือชี้ไปที่ null ทันทีระหว่างทำงาน ทำให้เครื่องมือประมวลผลภายในอย่าง OpenMP แตก (__kmp_invoke_microtask+152)