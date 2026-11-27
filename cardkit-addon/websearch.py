"""
เมนูคลิกขวาที่ข้อความที่เลือก → บรรทัดที่ตั้งเองได้ (ค้นหาเว็บ / สร้างภาพใน Gemini / ฯลฯ)
ตั้งค่าที่ CardKit → ✏️ ทบทวน / แก้ไข → "แก้เมนูคลิกขวา…"

แต่ละบรรทัด: ชื่อ · ลิงก์ · prompt
  {text}   = ข้อความที่เลือก
  {prompt} = prompt ที่เติม {text} แล้ว (ใส่ในลิงก์ได้)
  copy     = คัดลอก prompt ไว้ด้วย (เว็บที่ส่งข้อความทางลิงก์ไม่ได้ → กด Cmd/Ctrl+V วาง)
"""

from urllib.parse import quote_plus

from aqt import gui_hooks, mw
from aqt.qt import (
    QAction, QApplication, QCheckBox, QDialog, QDialogButtonBox, QHBoxLayout, QHeaderView, QLabel,
    QPushButton, QTableWidget, QTableWidgetItem, QUrl, QVBoxLayout,
)
from aqt.utils import openLink, tooltip

from .mqa import ADDON, cfg, on

CFG = "context_actions"
DEFAULTS = [
    {"label": "🔍 ค้นหาในเว็บ", "url": "https://www.google.com/search?q={prompt}",
     "prompt": "{text}", "copy": False},
    {"label": "🎨 สร้างภาพ", "url": "https://gemini.google.com/app?q={prompt}",
     "prompt": "สร้างภาพประกอบที่ช่วยให้จำเรื่องนี้ได้ง่าย เข้าใจทันทีที่เห็น: {text}", "copy": True},
]


def actions():
    v = cfg().get(CFG)
    return v if isinstance(v, list) else [dict(d) for d in DEFAULTS]


def _short(text):
    t = " ".join(text.split())
    return t if len(t) <= 24 else t[:22] + "…"


def _run(a, text):
    prompt = (a.get("prompt") or "{text}").replace("{text}", text)
    if a.get("copy"):
        QApplication.clipboard().setText(prompt)
    url = (a.get("url") or "").replace("{prompt}", quote_plus(prompt)).replace("{text}", quote_plus(text))
    if url:
        openLink(QUrl(url))
    if a.get("copy"):
        tooltip("คัดลอก prompt แล้ว · ถ้าเว็บไม่เติมให้ กด Cmd/Ctrl+V วาง", period=2500)


def _add(menu, text):
    text = (text or "").strip()
    if not text or not on("web_search"):
        return
    if any(x.text().startswith("​") for x in menu.actions()):
        return                                       # กันขึ้นซ้ำ (เมนูเดียวกันถูกเรียก 2 hook)
    first = menu.actions()[0] if menu.actions() else None
    added = 0
    for a in actions():
        label = (a.get("label") or "").strip()
        if not label:
            continue
        act = QAction("​" + f"{label} “{_short(text)}”", menu)
        act.triggered.connect(lambda _=False, a=a, t=text: _run(a, t))
        if first is not None:
            menu.insertAction(first, act)
        else:
            menu.addAction(act)
        added += 1
    if added and first is not None:
        menu.insertSeparator(first)


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


# ---------------------------------------------------------------- หน้าแก้เมนู

class ActionsDialog(QDialog):
    COLS = ("ชื่อในเมนู", "ลิงก์ (ใส่ {prompt})", "prompt (ใส่ {text})", "คัดลอก")

    def __init__(self, parent=None):
        super().__init__(parent or mw)
        self.setWindowTitle("CardKit · เมนูคลิกขวา")
        self.resize(900, 360)
        lay = QVBoxLayout(self)
        tip = QLabel("{text} = ข้อความที่เลือก · {prompt} = prompt ที่เติมข้อความแล้ว · "
                     "ติ๊ก \"คัดลอก\" = คัดลอก prompt ไว้ให้วางเอง (เช่น Gemini)")
        tip.setWordWrap(True)
        tip.setStyleSheet("color: gray;")
        lay.addWidget(tip)
        self.t = QTableWidget(0, 4)
        self.t.setHorizontalHeaderLabels(self.COLS)
        h = self.t.horizontalHeader()
        h.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        h.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        h.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        h.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        lay.addWidget(self.t)
        for a in actions():
            self._row(a)
        row = QHBoxLayout()
        for txt, fn in (("＋ เพิ่มบรรทัด", lambda: self._row({"label": "✨ ใหม่", "url": "", "prompt": "{text}"})),
                        ("－ ลบบรรทัดที่เลือก", self._del),
                        ("↑", lambda: self._move(-1)), ("↓", lambda: self._move(1)),
                        ("คืนค่าเดิม", self._reset)):
            b = QPushButton(txt)
            b.clicked.connect(fn)
            row.addWidget(b)
        row.addStretch(1)
        bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        row.addWidget(bb)
        lay.addLayout(row)

    def _row(self, a, at=None):
        r = self.t.rowCount() if at is None else at
        self.t.insertRow(r)
        for c, k in enumerate(("label", "url", "prompt")):
            self.t.setItem(r, c, QTableWidgetItem(a.get(k, "")))
        box = QCheckBox()
        box.setChecked(bool(a.get("copy")))
        self.t.setCellWidget(r, 3, box)

    def _get(self, r):
        return {"label": (self.t.item(r, 0).text() if self.t.item(r, 0) else ""),
                "url": (self.t.item(r, 1).text() if self.t.item(r, 1) else ""),
                "prompt": (self.t.item(r, 2).text() if self.t.item(r, 2) else ""),
                "copy": self.t.cellWidget(r, 3).isChecked()}

    def _del(self):
        r = self.t.currentRow()
        if r >= 0:
            self.t.removeRow(r)

    def _move(self, d):
        r = self.t.currentRow()
        n = r + d
        if r < 0 or n < 0 or n >= self.t.rowCount():
            return
        a = self._get(r)
        self.t.removeRow(r)
        self._row(a, n)
        self.t.setCurrentCell(n, 0)

    def _reset(self):
        self.t.setRowCount(0)
        for a in DEFAULTS:
            self._row(dict(a))

    def accept(self):
        c = cfg()
        c[CFG] = [self._get(r) for r in range(self.t.rowCount())]
        mw.addonManager.writeConfig(ADDON, c)
        super().accept()
        tooltip("บันทึกเมนูคลิกขวาแล้ว")


def open_dialog(parent=None):
    ActionsDialog(parent).exec()


gui_hooks.webview_will_show_context_menu.append(_on_webview_menu)
gui_hooks.editor_will_show_context_menu.append(_on_editor_menu)
