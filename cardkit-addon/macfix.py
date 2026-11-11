"""
Mac: แถบเมนูบนสุดหายเหลือแค่ "Anki  Edit"
สาเหตุ: หน้าต่างลอยเล็ก ๆ (panel / tool) หรือ "ไม่มีหน้าต่างไหน" รับคีย์บอร์ดอยู่ แทนหน้าต่างหลัก
ตัวนี้คอยดู: ถ้าโฟกัสหลุดไปแบบนั้น → คืนโฟกัสให้หน้าต่างหลัก (แถบเมนูกลับมา)
"""

import sys

from aqt import gui_hooks, mw
from aqt.qt import QApplication, QDialog, QEvent, QKeyEvent, QObject, Qt, QTimer

from .mqa import on

_timer = None
_last_real = None          # หน้าต่างจริงล่าสุดที่ใช้อยู่ (หน้าหลัก / Add / Edit / Browse …)


def _floating(w):
    try:
        t = w.windowType()
        return t in (Qt.WindowType.Tool, Qt.WindowType.ToolTip, Qt.WindowType.SplashScreen) \
            and not isinstance(w, QDialog)
    except Exception:
        return False


def _check():
    try:
        if not on("mac_menubar_fix") or mw is None or not mw.isVisible() or mw.isMinimized():
            return
        app = QApplication.instance()
        if app.applicationState() != Qt.ApplicationState.ApplicationActive:
            return                                   # ผู้ใช้อยู่แอปอื่น ไม่ยุ่ง
        if QApplication.activePopupWidget() is not None or QApplication.activeModalWidget() is not None:
            return
        aw = QApplication.activeWindow()
        if aw is not None and _floating(aw):
            # คืนโฟกัสให้หน้าต่างที่ใช้อยู่ก่อนหน้า (ไม่ใช่หน้าหลักเสมอ — เช่นกำลังพิมพ์ใน Add)
            w = _last_real if (_last_real is not None and _last_real.isVisible()) else mw
            w.activateWindow()
        # aw เป็น None = อาจกำลังใช้แผงอีโมจิ/แป้นพิมพ์ของ Mac → ไม่ยุ่ง
    except Exception:
        pass


def _soon(*_a):
    global _last_real
    try:
        aw = QApplication.activeWindow()
        if aw is not None and not _floating(aw):
            _last_real = aw
    except Exception:
        pass
    _timer.start(200)


# ---------------------------------------------------------------- อีโมจิจากแผง Mac (Ctrl+Cmd+Space)
# แผงอีโมจิส่งตัวอักษรมาแบบ "input method" บางทีไปตกที่ตัวหน้าต่าง/ตัว view แทนช่องที่พิมพ์อยู่
# → ช่องแก้การ์ด (หน้าเว็บ) ไม่ได้รับ · ตัวนี้ส่งต่อให้ช่องที่มีเคอร์เซอร์อยู่จริง

def _web_of(w):
    try:
        from aqt.qt import QWebEngineView
    except Exception:
        return None
    n = 0
    while w is not None and n < 10:
        if isinstance(w, QWebEngineView):
            return w
        w = w.parentWidget() if hasattr(w, "parentWidget") else None
        n += 1
    return None


class _EmojiFilter(QObject):
    def eventFilter(self, obj, ev):
        if ev.type() != QEvent.Type.InputMethod:
            return False
        try:
            if not on("mac_emoji_fix"):
                return False
            text = ev.commitString()
            if not text or ev.preeditString():
                return False                     # กำลังพิมพ์ไทย/ญี่ปุ่นแบบผสมคำ → ไม่ยุ่ง
            fw = QApplication.focusWidget()
            view = _web_of(fw)
            if view is None:
                return False
            target = view.focusProxy() or fw
            if obj is target:
                return False                     # ส่งถูกที่อยู่แล้ว
            for t in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease):
                QApplication.postEvent(target, QKeyEvent(t, Qt.Key.Key_unknown,
                                                         Qt.KeyboardModifier.NoModifier, text))
            return True
        except Exception:
            return False


_emoji = None


# ---- ทางหลัก: ดักที่ระดับ macOS ก่อน Qt ทิ้ง
# แผงอีโมจิเรียก insertText:replacementRange: ของ QNSView ตอนที่หน้าต่าง Anki ไม่ได้ถือคีย์บอร์ด
# (แผงแย่งโฟกัสไป) → ช่องแก้การ์ดถูก blur → Qt ไม่ส่งตัวอักษรต่อ (QTBUG-70319)
# ตัวนี้: ถ้า insertText ไม่ได้มาจากการกดปุ่มจริง และช่องที่ใช้อยู่เป็นหน้าเว็บ → คืนโฟกัส แล้วพิมพ์ให้เอง

_objc = {}


def _deliver(text):
    try:
        win = QApplication.activeWindow() or _last_real or mw
        fw = win.focusWidget() if win is not None else None
        view = _web_of(fw)
        if view is None:
            return False
        win.activateWindow()
        win.raise_()
        view.setFocus()

        def send():
            tgt = view.focusProxy() or view
            for t in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease):
                QApplication.postEvent(tgt, QKeyEvent(t, Qt.Key.Key_unknown,
                                                      Qt.KeyboardModifier.NoModifier, text))
        QTimer.singleShot(120, send)
        return True
    except Exception:
        return False


def _swizzle():
    if _objc or sys.platform != "darwin":
        return
    import ctypes
    from ctypes import CFUNCTYPE, Structure, c_bool, c_char_p, c_double, c_ulong, c_void_p

    objc = ctypes.cdll.LoadLibrary("/usr/lib/libobjc.A.dylib")
    objc.objc_getClass.restype = c_void_p
    objc.objc_getClass.argtypes = [c_char_p]
    objc.sel_registerName.restype = c_void_p
    objc.sel_registerName.argtypes = [c_char_p]
    objc.class_getInstanceMethod.restype = c_void_p
    objc.class_getInstanceMethod.argtypes = [c_void_p, c_void_p]
    objc.method_getImplementation.restype = c_void_p
    objc.method_getImplementation.argtypes = [c_void_p]
    objc.method_setImplementation.restype = c_void_p
    objc.method_setImplementation.argtypes = [c_void_p, c_void_p]
    send = objc.objc_msgSend

    def sel(n):
        return objc.sel_registerName(n.encode())

    def msg(obj, name, restype=c_void_p, argtypes=(), *args):
        send.restype = restype
        send.argtypes = [c_void_p, c_void_p] + list(argtypes)
        return send(obj, sel(name), *args)

    class NSRange(Structure):
        _fields_ = [("location", c_ulong), ("length", c_ulong)]

    cls = objc.objc_getClass(b"QNSView")
    if not cls:
        return
    m = objc.class_getInstanceMethod(cls, sel("insertText:replacementRange:"))
    if not m:
        return
    IMP = CFUNCTYPE(None, c_void_p, c_void_p, c_void_p, NSRange)
    orig = IMP(objc.method_getImplementation(m))
    app_cls = objc.objc_getClass(b"NSApplication")
    pi_cls = objc.objc_getClass(b"NSProcessInfo")

    def from_key_press():
        """กำลังกดปุ่มจริงอยู่ไหม (พิมพ์ปกติ = ใช่ · คลิกในแผงอีโมจิ = ไม่ใช่)"""
        app = msg(app_cls, "sharedApplication")
        ev = msg(app, "currentEvent")
        if not ev:
            return False
        if msg(ev, "type", c_ulong) != 10:          # NSEventTypeKeyDown
            return False
        now = msg(msg(pi_cls, "processInfo"), "systemUptime", c_double)
        return (now - msg(ev, "timestamp", c_double)) < 0.4

    def to_str(obj):
        if msg(obj, "respondsToSelector:", c_bool, [c_void_p], sel("string")):
            obj = msg(obj, "string")
        p = msg(obj, "UTF8String", c_char_p)
        return p.decode("utf-8") if p else ""

    def insert_text(self_, cmd, astr, rng):
        try:
            if on("mac_emoji_fix") and astr and not from_key_press():
                text = to_str(astr)
                if text and _deliver(text):
                    return
        except Exception:
            pass
        orig(self_, cmd, astr, rng)

    new = IMP(insert_text)
    _objc.update(orig=orig, new=new, keep=(NSRange, IMP))   # กันถูกเก็บกวาด
    objc.method_setImplementation(m, ctypes.cast(new, c_void_p))


def _swizzle_safe():
    try:
        _swizzle()
    except Exception:
        pass


def _install():
    global _timer
    if not sys.platform == "darwin" or _timer is not None:
        return
    _timer = QTimer(mw)
    _timer.setSingleShot(True)
    _timer.timeout.connect(_check)
    app = QApplication.instance()
    global _emoji
    _emoji = _EmojiFilter(mw)
    app.installEventFilter(_emoji)
    app.focusWindowChanged.connect(_soon)
    app.applicationStateChanged.connect(_soon)
    if on("mac_emoji_fix"):
        _swizzle_safe()
    gui_hooks.state_did_change.append(lambda *_a: _soon())
    gui_hooks.reviewer_did_show_question.append(lambda *_a: _soon())


gui_hooks.main_window_did_init.append(_install)
