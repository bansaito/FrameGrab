"""
Drop CSV Anywhere
-----------------
ลากไฟล์ CSV/TSV ไปวางที่หน้าต่างไหนของ Anki ก็ได้ (หน้าหลัก, Browse, Add,
Edit, Deck Options, Stats ฯลฯ) แล้วจะเปิดหน้าต่าง Import ให้ทันที

รองรับ Anki 2.1.55 ขึ้นไป (มี CSV importer แบบใหม่) และ fallback ไปตัว import เดิม
"""

import os
import time

from aqt import gui_hooks, mw
from aqt.qt import QApplication, QEvent, QObject, Qt, QTimer, QWidget
from aqt.utils import showWarning, tooltip

DEFAULT_EXTS = [".csv", ".tsv", ".txt"]
ADDON = __name__.split(".")[0]


def _load_config():
    try:
        cfg = mw.addonManager.getConfig(ADDON) or {}
    except Exception:
        cfg = {}
    exts = cfg.get("extensions", DEFAULT_EXTS) or DEFAULT_EXTS
    return {
        "extensions": tuple(
            (e if e.startswith(".") else "." + e).lower() for e in exts
        ),
        "show_tooltip": bool(cfg.get("show_tooltip", True)),
    }


CONFIG = {"extensions": tuple(DEFAULT_EXTS), "show_tooltip": True}


def _matching_paths(mime):
    """คืนรายชื่อไฟล์ ถ้าทุกไฟล์ที่ลากมาเป็นนามสกุลที่รองรับ ไม่งั้นคืน []
    (ถ้าลากรูปหรือไฟล์อื่นปนมา จะปล่อยให้ Anki จัดการตามปกติ เช่นวางรูปใน editor)"""
    if mime is None or not mime.hasUrls():
        return []
    urls = mime.urls()
    paths = []
    for u in urls:
        if not u.isLocalFile():
            return []
        p = u.toLocalFile()
        if not p.lower().endswith(CONFIG["extensions"]):
            return []
        paths.append(p)
    return paths


def _import_one(path):
    if mw.col is None:
        showWarning("ยังไม่ได้เปิดโปรไฟล์/คอลเลกชัน กรุณาเปิดโปรไฟล์ก่อน")
        return
    if not os.path.isfile(path):
        showWarning(f"ไม่พบไฟล์: {path}")
        return
    try:
        # Anki 2.1.55+
        from aqt.import_export.importing import import_file

        import_file(mw, path)
    except ImportError:
        # Anki รุ่นเก่า
        from aqt.importing import importFile

        importFile(mw, path)


class _DropFilter(QObject):
    WATCHED = (
        QEvent.Type.Show,
        QEvent.Type.DragEnter,
        QEvent.Type.DragMove,
        QEvent.Type.Drop,
    )

    def __init__(self, parent=None):
        super().__init__(parent)
        self._last_drop = (None, 0.0)

    def eventFilter(self, obj, ev):
        t = ev.type()
        if t not in self.WATCHED:
            return False

        # ทำให้ทุกหน้าต่างที่เปิดขึ้นมารับการ drop ได้
        if t == QEvent.Type.Show:
            if isinstance(obj, QWidget) and obj.isWindow():
                obj.setAcceptDrops(True)
            return False

        try:
            paths = _matching_paths(ev.mimeData())
        except Exception:
            return False
        if not paths:
            return False

        ev.setDropAction(Qt.DropAction.CopyAction)
        ev.accept()

        if t == QEvent.Type.Drop:
            # กัน event ซ้ำ (webview บางตัวส่ง drop ต่อกันภายใน)
            key = tuple(paths)
            last_key, last_time = self._last_drop
            now = time.monotonic()
            if key == last_key and now - last_time < 1.0:
                return True
            self._last_drop = (key, now)

            if CONFIG["show_tooltip"]:
                names = ", ".join(os.path.basename(p) for p in paths)
                tooltip(f"กำลังนำเข้า: {names}")
            # ทำหลัง drop จบ เพื่อไม่ให้ค้างระหว่างลากจาก Explorer/Finder
            for i, p in enumerate(paths):
                QTimer.singleShot(50 + i * 200, lambda p=p: _import_one(p))
        return True


_filter = None


def _install():
    global _filter, CONFIG
    if _filter is not None:
        return
    CONFIG = _load_config()
    app = QApplication.instance()
    _filter = _DropFilter(app)
    app.installEventFilter(_filter)
    # หน้าต่างที่เปิดอยู่แล้วก่อนติดตั้ง filter
    for w in app.topLevelWidgets():
        try:
            w.setAcceptDrops(True)
        except Exception:
            pass


def reload_config(*_args):
    global CONFIG
    CONFIG = _load_config()


gui_hooks.main_window_did_init.append(_install)
