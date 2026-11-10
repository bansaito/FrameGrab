"""
Mac: แถบเมนูบนสุดหายเหลือแค่ "Anki  Edit"
สาเหตุ: หน้าต่างลอยเล็ก ๆ (panel / tool) หรือ "ไม่มีหน้าต่างไหน" รับคีย์บอร์ดอยู่ แทนหน้าต่างหลัก
ตัวนี้คอยดู: ถ้าโฟกัสหลุดไปแบบนั้น → คืนโฟกัสให้หน้าต่างหลัก (แถบเมนูกลับมา)
"""

import sys

from aqt import gui_hooks, mw
from aqt.qt import QApplication, QDialog, Qt, QTimer

from .mqa import on

_timer = None


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
        if aw is None or _floating(aw):
            mw.activateWindow()
    except Exception:
        pass


def _soon(*_a):
    _timer.start(200)


def _install():
    global _timer
    if not sys.platform == "darwin" or _timer is not None:
        return
    _timer = QTimer(mw)
    _timer.setSingleShot(True)
    _timer.timeout.connect(_check)
    app = QApplication.instance()
    app.focusWindowChanged.connect(_soon)
    app.applicationStateChanged.connect(_soon)
    gui_hooks.state_did_change.append(lambda *_a: _soon())
    gui_hooks.reviewer_did_show_question.append(lambda *_a: _soon())


gui_hooks.main_window_did_init.append(_install)
