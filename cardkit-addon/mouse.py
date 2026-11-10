"""
ปุ่มเมาส์
  • ปุ่มกลาง (คลิก 1 ครั้ง) = พิมพ์ "  ตรงเคอร์เซอร์ (ช่องพิมพ์/ช่องแก้การ์ด)
  • ปุ่มข้าง 4 (Back)      = ย้อนกลับ:
        มีเมนู/ป๊อปอัปเปิดอยู่ → ปิด
        อยู่ในหน้าต่างย่อย (Edit, Add, Browse, ตั้งค่า …) → ปิดหน้าต่าง (เหมือนกดกากบาท) กลับไปหน้าเดิม
        หน้าต่างหลัก → กลับหน้า Decks (เหมือนกด D)
"""

from aqt import gui_hooks, mw
from aqt.qt import (
    QApplication, QDialog, QEvent, QHBoxLayout, QKeyEvent, QKeySequence, QLabel, QLineEdit,
    QObject, QPlainTextEdit, QPushButton, QTextEdit, Qt, QTimer, QVBoxLayout,
)

from .mqa import ADDON, cfg, on

_EXTRA_KEY = "mouse_quote_extra"     # ปุ่มที่สอนไว้ให้พิมพ์ " ด้วย เช่น ["m:16", "k:16777264:112"]


def _extras():
    v = cfg().get(_EXTRA_KEY) or []
    return set(v) if isinstance(v, list) else set()


def _sig_mouse(ev):
    return "m:%d" % ev.button().value


def _sig_key(ev):
    try:
        nv = int(ev.nativeVirtualKey())
    except Exception:
        nv = 0
    return "k:%d:%d" % (int(ev.key()), nv)

_BACK = Qt.MouseButton.BackButton      # = XButton1
_MID = Qt.MouseButton.MiddleButton


def _top_window(w):
    try:
        return w.window() if w is not None else None
    except Exception:
        return None


def _bubble_menu_close():
    try:
        from . import bubble
        b = bubble._bubble
        if b is not None and b.menu_open():
            b.menu.close()
            return True
    except Exception:
        pass
    return False


def _go_back(obj):
    # 1) เมนูป๊อปอัป (QMenu / คอมโบ)
    pop = QApplication.activePopupWidget()
    if pop is not None:
        pop.close()
        return
    # 2) เมนูปุ่มลอย Rounds
    if _bubble_menu_close():
        return
    # 3) หน้าต่างย่อย → ปิด
    win = QApplication.activeWindow() or _top_window(obj)
    if win is not None and win is not mw:
        if isinstance(win, QDialog):
            win.reject()                # = Esc (Edit ปัจจุบันบันทึกให้เองตอนปิด)
        else:
            win.close()                 # = กากบาท (Browse ฯลฯ)
        return
    # 4) หน้าต่างหลัก → Decks
    if mw.state != "deckBrowser":
        mw.moveToState("deckBrowser")


def _type_quote(obj):
    w = QApplication.focusWidget()
    if w is None:
        return False
    if isinstance(w, (QLineEdit, QTextEdit, QPlainTextEdit)):
        if w.isReadOnly():
            return False
        w.insertPlainText('"') if not isinstance(w, QLineEdit) else w.insert('"')
        return True
    # หน้าเว็บ (ช่องแก้การ์ด / ช่องพิมพ์คำตอบ): ส่งปุ่ม " ให้เหมือนพิมพ์เอง
    view = _web_of(w) or _web_of(obj)
    if view is None:
        return False
    target = view.focusProxy() or w
    for t in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease):
        QApplication.postEvent(target, QKeyEvent(t, Qt.Key.Key_QuoteDbl, Qt.KeyboardModifier.NoModifier, '"'))
    return True


def _web_of(w):
    """หา QWebEngineView ที่ widget นี้อยู่ข้างใน (ตัวรับปุ่มจริงเป็น widget ลูกที่ PyQt ไม่รู้จักชื่อ)"""
    try:
        from aqt.qt import QWebEngineView
    except Exception:
        return None
    n = 0
    while w is not None and n < 8:
        if isinstance(w, QWebEngineView):
            return w
        w = w.parentWidget() if hasattr(w, "parentWidget") else None
        n += 1
    return None


class _Filter(QObject):
    def eventFilter(self, obj, ev):
        t = ev.type()
        if t in (QEvent.Type.KeyPress, QEvent.Type.ShortcutOverride) and _teach is None:
            try:
                ex = _extras()
                if ex and on("mouse_mid_quote") and _sig_key(ev) in ex:
                    if t == QEvent.Type.KeyPress and not ev.isAutoRepeat():
                        _type_quote(obj)
                    return True
            except Exception:
                pass
            return False
        if t not in (QEvent.Type.MouseButtonPress, QEvent.Type.MouseButtonRelease,
                     QEvent.Type.MouseButtonDblClick):
            return False
        if _teach is not None:
            return False
        try:
            b = ev.button()
            ex = _extras()
            if ex and _sig_mouse(ev) in ex and b not in (Qt.MouseButton.LeftButton, Qt.MouseButton.RightButton) \
                    and on("mouse_mid_quote"):
                if t == QEvent.Type.MouseButtonPress:
                    _type_quote(obj)
                return True
            if b == _BACK and on("mouse_back"):
                if t == QEvent.Type.MouseButtonPress:
                    QTimer.singleShot(0, lambda o=obj: _go_back(o))
                return True               # กิน press/release ทั้งหมด (กันเว็บย้อนหน้าเอง)
            if b == _MID and on("mouse_mid_quote"):
                if t == QEvent.Type.MouseButtonPress:
                    if not _type_quote(obj):
                        return False      # ไม่ได้อยู่ในช่องพิมพ์ → ปล่อยตามเดิม
                    self._ate_mid = True
                    return True
                if getattr(self, "_ate_mid", False):
                    if t == QEvent.Type.MouseButtonRelease:
                        self._ate_mid = False
                    return True
        except Exception:
            pass
        return False


# ---------------------------------------------------------------- สอนปุ่มเพิ่ม (เช่นปุ่ม DPI)

_teach = None


class _TeachDialog(QDialog):
    def __init__(self):
        super().__init__(mw)
        self.setWindowTitle("สอนปุ่มพิมพ์ \"")
        self.setMinimumWidth(380)
        lay = QVBoxLayout(self)
        self.lab = QLabel("กดปุ่มที่อยากให้พิมพ์ \" ได้เลย\n(เช่นปุ่ม DPI บนเมาส์ · ห้ามใช้คลิกซ้าย/ขวา)")
        self.lab.setWordWrap(True)
        lay.addWidget(self.lab)
        self.got = QLabel("")
        self.got.setWordWrap(True)
        lay.addWidget(self.got)
        row = QHBoxLayout()
        clear = QPushButton("ล้างปุ่มที่สอนไว้")
        clear.clicked.connect(self._clear)
        close = QPushButton("เสร็จ")
        close.clicked.connect(self.accept)
        row.addWidget(clear)
        row.addStretch(1)
        row.addWidget(close)
        lay.addLayout(row)
        n = len(_extras())
        if n:
            self.got.setText(f"ตอนนี้สอนไว้แล้ว {n} ปุ่ม")
        self._hint = QTimer(self)
        self._hint.setSingleShot(True)
        self._hint.timeout.connect(lambda: self.got.text() or self.got.setText(
            "ยังไม่เห็นสัญญาณจากปุ่ม — ถ้ากดแล้วไม่ขึ้นอะไร แปลว่าปุ่มนี้เมาส์ใช้เอง (ไม่ส่งมาที่คอม)\n"
            "ทางแก้: ในโปรแกรมของเมาส์ (เช่น Logi Options+, Razer Synapse) ตั้งปุ่มนั้นเป็น \"Middle click\""))
        self._hint.start(8000)

    def _save(self, sig, label):
        c = cfg()
        cur = list(c.get(_EXTRA_KEY) or [])
        if sig not in cur:
            cur.append(sig)
        c[_EXTRA_KEY] = cur
        mw.addonManager.writeConfig(ADDON, c)
        self.got.setText(f"✓ จำแล้ว: {label}\nกดปุ่มอื่นเพิ่มได้ หรือกด เสร็จ")

    def _clear(self):
        c = cfg()
        c[_EXTRA_KEY] = []
        mw.addonManager.writeConfig(ADDON, c)
        self.got.setText("ล้างแล้ว")

    def catch(self, ev):
        t = ev.type()
        if t == QEvent.Type.MouseButtonPress:
            b = ev.button()
            if b in (Qt.MouseButton.LeftButton, Qt.MouseButton.RightButton):
                return False
            self._save(_sig_mouse(ev), f"ปุ่มเมาส์ #{b.value}")
            return True
        if t == QEvent.Type.KeyPress:
            if ev.key() in (Qt.Key.Key_Escape, Qt.Key.Key_Return, Qt.Key.Key_Enter, Qt.Key.Key_Space,
                            Qt.Key.Key_Tab):
                return False
            if ev.text() and ev.text().isprintable():
                self.got.setText("ปุ่มนี้เป็นตัวอักษรธรรมดา ไม่จำ (กันพิมพ์ปกติไม่ได้)")
                return True
            self._save(_sig_key(ev), "ปุ่ม " + (QKeySequence(ev.key()).toString() or str(ev.key())))
            return True
        return False


class _TeachFilter(QObject):
    def eventFilter(self, obj, ev):
        d = _teach
        if d is None:
            return False
        try:
            return d.catch(ev)
        except Exception:
            return False


def teach():
    global _teach
    d = _TeachDialog()
    f = _TeachFilter(d)
    _teach = d
    QApplication.instance().installEventFilter(f)

    def done(*_a):
        global _teach
        _teach = None
        QApplication.instance().removeEventFilter(f)

    d.finished.connect(done)
    d.show()


_filter = None


def _install():
    global _filter
    if _filter is None:
        _filter = _Filter(mw)
        QApplication.instance().installEventFilter(_filter)


gui_hooks.main_window_did_init.append(_install)
