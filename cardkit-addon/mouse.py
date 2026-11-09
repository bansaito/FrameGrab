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
    QApplication, QDialog, QEvent, QKeyEvent, QLineEdit, QObject, QPlainTextEdit,
    QTextEdit, Qt, QTimer,
)

from .mqa import on

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
    if type(w).__name__.startswith("RenderWidgetHostView") or "WebEngine" in type(w).__name__ \
            or "WebView" in type(w).__name__:
        for t in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease):
            QApplication.postEvent(w, QKeyEvent(t, Qt.Key.Key_QuoteDbl, Qt.KeyboardModifier.NoModifier, '"'))
        return True
    return False


class _Filter(QObject):
    def eventFilter(self, obj, ev):
        t = ev.type()
        if t not in (QEvent.Type.MouseButtonPress, QEvent.Type.MouseButtonRelease,
                     QEvent.Type.MouseButtonDblClick):
            return False
        try:
            b = ev.button()
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


_filter = None


def _install():
    global _filter
    if _filter is None:
        _filter = _Filter(mw)
        QApplication.instance().installEventFilter(_filter)


gui_hooks.main_window_did_init.append(_install)
