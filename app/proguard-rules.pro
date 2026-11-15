# อ่านรายงานแอปปิดเองได้ง่าย (ชื่อคลาส/ฟังก์ชันไม่ถูกเปลี่ยน)
-dontobfuscate

# Tesseract / Leptonica เรียกกลับจากโค้ด native — ห้ามตัดทิ้ง
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
