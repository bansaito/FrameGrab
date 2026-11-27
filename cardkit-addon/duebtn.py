"""
แถบด้านบน (Decks Add Browse Stats Sync): ระหว่างท่อง ชี้เมาส์ทางขวาของ Sync → ปุ่ม "📅 Set Due Date ⇧⌘D" โผล่
กด = Set Due Date ของการ์ดที่กำลังท่อง (เหมือน More → Set Due Date / Ctrl+Shift+D)
"""

import sys

from aqt import gui_hooks, mw

from .mqa import on

_ID = "ck-due"
_CSS = (f"<style>#{_ID}{{opacity:0;transition:opacity .15s ease;background:none!important;"
        f"border:none!important;box-shadow:none!important;outline:none!important;padding:0 4px!important;"
        f"white-space:nowrap;z-index:50;color:var(--fg,#e6e6e6)!important;text-decoration:none!important;"
        f"font-weight:600;cursor:pointer}}"
        f"#{_ID}:hover{{opacity:1;background:none!important}}</style>")
# ย้ายปุ่มออกจากแถบ ไปลอยข้าง Sync (ไม่ยืดแถบ ไม่มีกรอบ)
_PLACE_JS = f"""<script>(function(){{
  function place(){{
    var e = document.getElementById('{_ID}'); if (!e) return;
    var hs = document.querySelectorAll('.hitem'), s = document.getElementById('sync');
    if (!s) {{ for (var i = hs.length - 1; i >= 0; i--) if (hs[i] !== e) {{ s = hs[i]; break; }} }}
    if (!s) return;
    if (e.parentNode !== document.body) document.body.appendChild(e);
    var r = s.getBoundingClientRect();
    e.style.position = 'fixed'; e.style.left = (r.right + 16) + 'px'; e.style.top = r.top + 'px';
    e.style.height = r.height + 'px'; e.style.lineHeight = r.height + 'px'; e.style.display = e.style.display;
  }}
  place(); setTimeout(place, 100); setTimeout(place, 600);
  window.addEventListener('resize', place);
}})();</script>"""


def _click():
    if mw.state != "review" or getattr(mw.reviewer, "card", None) is None:
        return
    try:
        mw.reviewer.on_set_due()
    except Exception as e:
        print("[cardkit due btn]", e)


def _show_js(show):
    return (f"(function(){{var e=document.getElementById('{_ID}');"
            f"if(e) e.style.display='{'' if show else 'none'}';}})();")


def _on_links(links, toolbar):
    if not on("due_button"):
        return
    try:
        keys = "⇧⌘D" if sys.platform == "darwin" else "Ctrl+Shift+D"
        label = "📅 Set Due Date"
        html = toolbar.create_link("ck_set_due", label, _click,
                                   tip=f"Set Due Date ของการ์ดนี้ ({keys})", id=_ID)
        html = html.replace(f">{label}</a>", f">{label}<span style=\"opacity:.5;font-size:.8em;"
                                              f"margin-left:8px\">{keys}</span></a>", 1)
        if mw.state != "review":
            html = html.replace("<a ", '<a style="display:none" ', 1)
        links.append(_CSS + html + _PLACE_JS)
    except Exception as e:
        print("[cardkit due btn]", e)


def _on_state(new, _old):
    try:
        mw.toolbar.web.eval(_show_js(new == "review" and on("due_button")))
    except Exception:
        pass


gui_hooks.top_toolbar_did_init_links.append(_on_links)
gui_hooks.state_did_change.append(_on_state)


# ---------------------------------------------------------------- หน้าต่าง Set Due Date แบบกดง่าย
# ใช้ได้ทุกทาง (ปุ่มบนแถบ / ⇧⌘D / More / Browse): ▲▼ · ลูกศรขึ้นลง · ลูกกลิ้ง · ปุ่มลัด 7 14 21 30 60 120 180

import re as _re

PRESETS = (0, 1, 7, 14, 21, 30, 60, 120, 180)


def _bump(text, d):
    """เพิ่ม/ลดตัวเลข เก็บรูปแบบเดิม ("5!" → "6!", "3-7" → "4-8")"""
    t = (text or "").strip() or "0"
    m = _re.match(r"^(\d+)\s*-\s*(\d+)(.*)$", t)
    if m:
        a, b = max(0, int(m.group(1)) + d), max(0, int(m.group(2)) + d)
        return f"{a}-{b}{m.group(3)}"
    m = _re.match(r"^(\d+)(.*)$", t)
    if m:
        return f"{max(0, int(m.group(1)) + d)}{m.group(2)}"
    return t


def _due_dialog(prompt, parent, default, title):
    from aqt.qt import (
        QDialog, QDialogButtonBox, QEvent, QHBoxLayout, QLabel, QLineEdit, QObject, QPushButton, Qt,
        QVBoxLayout,
    )

    d = QDialog(parent)
    d.setWindowTitle(title)
    lay = QVBoxLayout(d)
    lay.addWidget(QLabel(prompt))
    row = QHBoxLayout()
    e = QLineEdit(default or "1")
    e.selectAll()
    row.addWidget(e, 1)
    for txt, step in (("▲", 1), ("▼", -1)):
        b = QPushButton(txt)
        b.setFixedWidth(36)
        b.setAutoRepeat(True)
        b.clicked.connect(lambda _=False, s=step: e.setText(_bump(e.text(), s)))
        row.addWidget(b)
    lay.addLayout(row)
    pre = QHBoxLayout()
    for v in PRESETS:
        b = QPushButton("วันนี้" if v == 0 else str(v))
        b.setToolTip("วันนี้" if v == 0 else f"อีก {v} วัน")
        b.clicked.connect(lambda _=False, v=v: e.setText(str(v)))
        b.setMinimumWidth(38)
        pre.addWidget(b)
    lay.addLayout(pre)
    hint = QLabel("▲▼ / ลูกศรขึ้นลง / ลูกกลิ้ง = เปลี่ยนเลข · Enter = ตกลง")
    hint.setStyleSheet("color: gray; font-size: 11px;")
    lay.addWidget(hint)
    bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
    bb.accepted.connect(d.accept)
    bb.rejected.connect(d.reject)
    lay.addWidget(bb)

    class _F(QObject):
        def eventFilter(self, obj, ev):
            t = ev.type()
            if t == QEvent.Type.KeyPress and ev.key() in (Qt.Key.Key_Up, Qt.Key.Key_Down):
                e.setText(_bump(e.text(), 1 if ev.key() == Qt.Key.Key_Up else -1))
                return True
            if t == QEvent.Type.Wheel:
                dy = ev.angleDelta().y()
                if dy:
                    e.setText(_bump(e.text(), 1 if dy > 0 else -1))
                return True
            return False

    f = _F(d)
    e.installEventFilter(f)
    d.installEventFilter(f)
    e.setFocus()
    ok = d.exec()
    return e.text(), bool(ok)


def _patch_due_dialog():
    try:
        import aqt.operations.scheduling as S
        from aqt.utils import tr
        orig = S.getText

        def get_text(prompt, parent=None, help=None, edit=None, default="", title="Anki", **kw):
            try:
                if on("due_dialog") and title == tr.actions_set_due_date():
                    return _due_dialog(prompt, parent, default, title)
            except Exception as ex:
                print("[cardkit due dialog]", ex)
            return orig(prompt, parent=parent, help=help, edit=edit, default=default, title=title, **kw)

        S.getText = get_text
    except Exception as ex:
        print("[cardkit due dialog] patch", ex)


_patch_due_dialog()
