"""
คลิกขวาที่ข้อความที่เลือก → "🔍 ค้นหา "…" ในเว็บ" → เปิด Google ในเบราว์เซอร์หลักของเครื่อง
ใช้ได้ในหน้าการ์ด (ทบทวน/พรีวิว) และช่องแก้การ์ด (Add / Edit / Browse)
"""

from urllib.parse import quote_plus

from aqt import gui_hooks
from aqt.qt import QAction, QUrl
from aqt.utils import openLink

from .mqa import on


def _label(text):
    t = " ".join(text.split())
    return t if len(t) <= 24 else t[:22] + "…"


def _add(menu, text):
    text = (text or "").strip()
    if not text or not on("web_search"):
        return
    if any(a.text().startswith("🔍 ค้นหา") for a in menu.actions()):
        return                                       # กันขึ้นซ้ำ
    act = QAction(f"🔍 ค้นหา “{_label(text)}” ในเว็บ", menu)
    act.triggered.connect(lambda _=False, t=text: openLink(QUrl(
        "https://www.google.com/search?q=" + quote_plus(t))))
    first = menu.actions()[0] if menu.actions() else None
    if first is not None:
        menu.insertAction(first, act)
        menu.insertSeparator(first)
    else:
        menu.addAction(act)


def _on_webview_menu(webview, menu):
    try:
        _add(menu, webview.selectedText())
    except Exception:
        pass


def _on_editor_menu(editor_webview, menu):
    try:
        _add(menu, editor_webview.selectedText())
    except Exception:
        pass


gui_hooks.webview_will_show_context_menu.append(_on_webview_menu)
gui_hooks.editor_will_show_context_menu.append(_on_editor_menu)
