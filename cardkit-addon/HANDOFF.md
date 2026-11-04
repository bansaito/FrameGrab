# Handoff: CardKit (Anki desktop add-on) + AnkiRounds (Android app)

> วางไฟล์นี้ในแชทใหม่ แล้วบอกว่า "ทำงานต่อจาก handoff นี้"
> ผู้ใช้: คุยภาษาไทย ชอบคำอธิบายสั้น ง่าย · ชอบให้จัด **tier list (S/A/B/C)** ก่อนทำ · ถ้าพูดว่า "อย่าเพิ่งทำไร" = เสนออย่างเดียว ห้ามสร้าง
> ฟีเจอร์ใหม่ทำเป็น **สวิตช์เปิด/ปิด** ได้เสมอ (ค่าเริ่มต้นตามที่ผู้ใช้บอก) · ไม่ชอบตัวเลข/ความรกในป๊อปอัป

## ที่อยู่โค้ด
- Repo: `bansaito/FrameGrab` branch **`anki-rounds`** (ใช้ `add_repo` แล้ว clone)
  - Android app: `app/` (Kotlin, package `com.v2f.ankirounds`) — push แล้ว GitHub Actions build + ปล่อย APK อัตโนมัติ
  - APK: https://github.com/bansaito/FrameGrab/releases/download/ankirounds-latest/AnkiRounds.apk
  - ทุกครั้งที่แก้แอป: เพิ่ม `versionCode`/`versionName` ใน `app/build.gradle.kts` (ล่าสุด 37 / 1.36) แล้ว push · รอ `gh run list -b anki-rounds`
  - Desktop add-on source: `cardkit-addon/` (ไม่ trigger build) — แพ็กเป็น `.ankiaddon` ด้วย `cd cardkit-addon && zip -r ../drop_csv_anywhere.ankiaddon . -x '__pycache__/*'` แล้วส่งไฟล์ให้ผู้ใช้
- ผู้ใช้มี 2 เครื่อง: Xiaomi 14T + Samsung S23 (มือถือ), Mac (Anki 26.09, PyQt 6.11) + Windows (คอม)

## CardKit (add-on คอม) — ไฟล์
- `drop.py` ลาก CSV มาวางเพื่อ import · `preview.py` พรีวิวการ์ดในหน้า Add (Ctrl+Shift+Y), ปุ่ม $→MathJax
- `mqa.py` + `mqa_parse.py` การ์ดหลายข้อ: เฉลยใต้ข้อ, **คลิก ก ข ค ง ตอบ** (✅❌), 💡 ย่อเฉลย, ย้ายข้อ (Ctrl+Shift+M), เมนู 🃏 CardKit (ชี้เมาส์แล้วเปิด, คลิกสำคัญกว่า)
  - parser รองรับ: "ข้อ/ข้อที่/ข้อหลัก/ข้อย่อย(ที่) N", เลขลอย "12."/"3.4", เฉลย "ข้อ N ตอบ ก.", "N. ตอบ ก.", "N. ก.xxx", "เฉลยข้อ N: ก.", การ์ดข้อเดียว+เฉลย "ข.xxx" (ไม่มีเลข) · คำอธิบาย "1. เพราะ…" ไม่ถูกนับ · กลุ่มที่นับเป็นข้อต้องมีตัวเลือก ≥2 (กรณีข้อเดียว)
  - คำตอบที่เลือกจำตาม **เลขข้อ (data-n)** และ id การ์ด (data-k)
  - ค่าเริ่มต้นปัจจุบัน: inline+stealth+hidebtn+accordion ON, click_choice+noframe ON, compact OFF, split OFF, hover_open ON
- `rounds.py` เมนู **📚 Rounds**: ชุด deck (≤3) แต่ละ deck มี base/step · ➕ อีก 1 รอบ · ↺ ต่อชุด (ไอคอนเส้น) · 🔍/ลูกตา hover = due ทั้งหมด (วันนี้+ค้าง, สีแบบ Anki) · ลากเรียงชุดในเมนูได้ · ชุดของ deck ที่เลือกขึ้นบนสุด (สวิตช์) · **↺ เริ่มใหม่ทุกชุด + ↶ ยกเลิก** · โหมด Today only (สวิตช์) · หน้าตั้งค่าชุด (ลากเรียง, เลือก deck แบบต้นไม้พับได้+ค้นหา)
  - หน้า Congratulations: แทรกตาราง New/Learn/Due ของชุด (deck แม่พับ –/+ ได้ จำใน config) + ลูกตา = due ที่เหลือ · คลิกชื่อ = ไปท่อง (`pycmd cardkit_go:<did>`) · ใช้ `overview_did_refresh` + JS inject (หน้า congrats เป็น sveltekit)
- `bubble.py` **ปุ่มลอยวงกลม** แบบมือถือ (หน้า Decks/Overview): 📚 น้ำเงิน / ➕ เขียว + วงแหวน, จาง, **ชิดขอบซ่อนครึ่ง** (สวิตช์), ลากย้าย, เมนู: ชุดหลัก (deck ล่าสุด→ชุดที่ใช้ล่าสุด) ➕ ↶ย้อน/↶ยกเลิก ↺ · ลูกตาเส้น (กดค้าง = new·due) · ชิปชุดอื่น · ↺/↶ทุกชุด ⚙️ ✕
  - บั๊กที่เคยเจอ: Menu ใช้ WA_DeleteOnClose → อ้างถึงต้องผ่าน `menu_open()` (กัน RuntimeError)

## AnkiRounds (แอปมือถือ) — สรุป (v1.36)
- AccessibilityService กดแทนใน AnkiDroid: long-press deck → Deck options → ช่อง Maximum reviews/day (Preset) → Save · อ่านค่า/จำนวนผ่าน AnkiDroid ContentProvider API
- เร่งความเร็วแล้ว (อ่านหน้าครั้งเดียว, เฉพาะหน้าต่าง AnkiDroid, ส่วนที่เห็นบนจอ) ~3.5 วิ/deck
- ปุ่มลอย: แสดงหน้าไหนได้ (สวิตช์รายหน้า), 2 สถานะ+วงแหวน, จาง, ชัดเฉพาะเปิดครั้งแรก (ออกไป >30 นาที), เก็บในแจ้งเตือน, ซ่อมตัวเองทุก 5 วิ
- เมนู: ชุดหลัก = ชุดของ deck ที่ท่องล่าสุด (selected deck) → ชุดที่ใช้ล่าสุด · ➕ · ↶ย้อน / ↶ยกเลิก(หลัง ↺) · ↺ แสดงเสมอ กดครั้งเดียว · ลูกตาจาง (กดค้าง = new น้ำเงิน · due ทั้งหมด เขียว ผ่าน notes search `is:due`) · ชิปชุดอื่น · 🏦 ⚙️ 📥 ✕ · ไม่ปิดเอง · เมนูไม่ทับปุ่ม · แตะปุ่มลอย = toggle
- โหมดธนาคาร (+อัตโนมัติเมื่อเปิดแอปธนาคารที่เลือก, ปิดไว้) · WRITE_SECURE_SETTINGS ผ่าน adb = เปิด/ปิดผู้ช่วยแตะเดียว (Xiaomi ต้องเปิด "USB debugging (Security settings)")
- ส่ง log: แอป → 📤 ส่ง log (มี timestamp ทุกขั้น)

## งานค้าง / ไอเดียที่คุยไว้แต่ยังไม่ทำ
- 2/1/1 อัตโนมัติทุกเช้าหลังตี 4 บนมือถือ (แก้ช่อง Today only แทน Preset) — ผู้ใช้ให้ข้ามไว้ก่อน
- ปุ่ม "เริ่มใหม่ทุกชุด" ในแอปมือถือ (ทำแล้วเฉพาะคอม)
- หน้า Congratulations: ปุ่ม "➕ อีก 1 รอบ แล้วท่องต่อ" / auto กลับหน้า Decks (ยังไม่ได้เลือก)
