"""
คีย์ลัดใช้ได้ทุกภาษาแป้นพิมพ์ (เช่นแป้นไทยเกษมณี)
ดูจาก "ตำแหน่งปุ่ม" ที่กดจริง ไม่ใช่ตัวอักษรที่พิมพ์ออกมา:
  • Windows: virtual-key (VK) ของตัวอักษร/ตัวเลขเป็นอังกฤษเสมอ
  • macOS:   kVK keycode ของปุ่ม
  • สำรอง:   แปลงตัวอักษรไทยกลับเป็นปุ่มอังกฤษตามผังเกษมณี
"""

import sys

from aqt import gui_hooks, mw
from aqt.qt import QEvent, QObject, Qt

# ผังเกษมณี: ตัวไทย → ปุ่มอังกฤษ (ไม่รวม "/" "-" เพราะซ้ำกับแป้นอังกฤษ — ใช้ตำแหน่งปุ่มแทน)
THAI = {
    "ๅ": "1", "ภ": "4", "ถ": "5", "ุ": "6", "ึ": "7", "ค": "8", "ต": "9", "จ": "0",
    "ๆ": "Q", "ไ": "W", "ำ": "E", "พ": "R", "ะ": "T", "ั": "Y", "ี": "U", "ร": "I", "น": "O", "ย": "P",
    "ฟ": "A", "ห": "S", "ก": "D", "ด": "F", "เ": "G", "้": "H", "่": "J", "า": "K", "ส": "L",
    "ผ": "Z", "ป": "X", "แ": "C", "อ": "V", "ิ": "B", "ื": "N", "ท": "M",
    # Shift
    "ฑ": "R", "ฎ": "E", "ฆ": "S", "ฌ": "G", "(": "Z",
}

# macOS kVK (ตำแหน่งปุ่ม)
MAC = {
    0x12: "1", 0x13: "2", 0x14: "3", 0x15: "4", 0x17: "5", 0x16: "6", 0x1A: "7", 0x1C: "8",
    0x19: "9", 0x1D: "0",
    0x00: "A", 0x0B: "B", 0x08: "C", 0x02: "D", 0x0E: "E", 0x03: "F", 0x05: "G", 0x04: "H",
    0x22: "I", 0x26: "J", 0x28: "K", 0x25: "L", 0x2E: "M", 0x2D: "N", 0x1F: "O", 0x23: "P",
    0x0C: "Q", 0x0F: "R", 0x01: "S", 0x11: "T", 0x20: "U", 0x09: "V", 0x0D: "W", 0x07: "X",
    0x10: "Y", 0x06: "Z",
}


def latin(ev):
    """ปุ่มอังกฤษ/ตัวเลขของปุ่มที่กด ("G", "1", …) ไม่ว่าแป้นเป็นภาษาอะไร · ไม่ใช่ตัวอักษร = None"""
    t = ev.text() or ""
    if len(t) == 1 and t.isascii() and t.isalnum():
        return t.upper()
    if t and not t.isascii() and t in THAI:
        return THAI[t]                           # แป้นไทยเกษมณี (แน่นอนกว่า)
    try:
        vk = int(ev.nativeVirtualKey())
    except Exception:
        vk = 0
    if sys.platform == "darwin":
        if vk in MAC:
            return MAC[vk]
    elif sys.platform.startswith("win"):
        if 0x30 <= vk <= 0x39 or 0x41 <= vk <= 0x5A:
            return chr(vk)
    return None


def is_non_latin(ev):
    """กดตอนแป้นเป็นภาษาอื่น (ตัวที่ได้ไม่ใช่อักษรอังกฤษ/ตัวเลข)"""
    t = ev.text() or ""
    return bool(t) and not (t.isascii() and t.isalnum())


# ---------------------------------------------------------------- ตัวกลางรับปุ่ม (แป้นไทย)
# ปุ่มที่ Anki/คีย์ลัดเดิมจับไม่ได้ตอนแป้นเป็นไทย → แปลงแล้วส่งให้ handler ที่ลงทะเบียนไว้

_handlers = {}     # (state, "G") → fn


def register(states, key, fn):
    for s in states:
        _handlers[(s, key)] = fn


def unregister_state(state, keys):
    for k in keys:
        _handlers.pop((state, k), None)


class _Filter(QObject):
    def eventFilter(self, obj, ev):
        t = ev.type()
        if t not in (QEvent.Type.ShortcutOverride, QEvent.Type.KeyPress):
            return False
        try:
            if mw is None or not mw.isActiveWindow():
                return False
            if ev.modifiers() & (Qt.KeyboardModifier.ControlModifier | Qt.KeyboardModifier.AltModifier
                                 | Qt.KeyboardModifier.MetaModifier):
                return False
            if not is_non_latin(ev):
                return False                     # แป้นอังกฤษ: ให้คีย์ลัดเดิมทำงาน
            key = latin(ev)
            fn = _handlers.get((mw.state, key)) if key else None
            if fn is None:
                return False
            if t == QEvent.Type.ShortcutOverride:
                ev.accept()                      # กันคีย์ลัดอื่น (เช่น "/" = ปุ่ม 2 บนแป้นไทย)
                return True
            if not ev.isAutoRepeat():
                fn()
            return True
        except Exception:
            return False


_filter = None


def _install():
    global _filter
    if _filter is None:
        from aqt.qt import QApplication
        _filter = _Filter(mw)
        QApplication.instance().installEventFilter(_filter)


gui_hooks.main_window_did_init.append(_install)
