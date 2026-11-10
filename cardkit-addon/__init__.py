"""
Drop CSV Anywhere + Live Preview
  • drop.py    – ลากไฟล์ CSV/TSV ไปวางที่หน้าต่างไหนของ Anki ก็ได้ เพื่อเปิดหน้า Import
  • preview.py – แผงพรีวิวการ์ดแบบสดในหน้า Add และปุ่มแปลง $...$ เป็น MathJax
  • mqa.py     – การ์ดหลายข้อ: เฉลยใต้แต่ละข้อ, คลิกตอบ, ย้ายข้อ
  • rounds.py  – ท่องเป็นรอบ: เพิ่ม Maximum reviews/day ทีละรอบให้ทั้งชุด deck
  • bubble.py  – ปุ่มลอยวงกลมของ Rounds (แบบแอปมือถือ)
  • mouse.py   – ปุ่มเมาส์กลาง = " , ปุ่มข้าง (Back) = ย้อนกลับ
"""

from aqt import mw

from . import keymap  # noqa: F401  (คีย์ลัดใช้ได้ทั้งแป้นไทย/อังกฤษ)

from . import drop, mqa, preview  # noqa: F401
from . import rounds  # noqa: F401  (โหลดหลัง mqa เพื่อวางเมนูถัดจาก CardKit)
from . import bubble  # noqa: F401  (ปุ่มลอย Rounds แบบมือถือ)
from . import decktree  # noqa: F401  (Choose Deck แบบต้นไม้)
from . import macfix  # noqa: F401  (Mac: กันแถบเมนูหาย)
from . import mouse  # noqa: F401  (ปุ่มเมาส์กลาง = ", ปุ่มข้าง = ย้อนกลับ)

mw.addonManager.setConfigUpdatedAction(__name__, drop.reload_config)
