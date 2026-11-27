"""
ตั้งคีย์ลัดเอง (CardKit → ตั้งค่า → ⌨ ตั้งคีย์ลัด…)
เก็บใน config "shortcuts": {"plus": ["F", "Space"], …} · ชื่อปุ่มเป็นตำแหน่งปุ่มแบบอังกฤษ ใช้ได้ทุกภาษาแป้น
"""

from aqt import mw
from aqt.qt import (
    QDialog, QDialogButtonBox, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton, Qt,
    QVBoxLayout,
)
from aqt.utils import showWarning, tooltip

from .mqa import ADDON, cfg

CFG = "shortcuts"

# (id, ชื่อที่แสดง, ค่าเริ่มต้น)
COMMANDS = [
    ("menu", "เปิด/ปิดเมนูปุ่มลอย (หน้า Decks / หน้า deck)", ["G"]),
    ("plus_now", "อีก 1 รอบ ทันที ไม่ต้องเปิดเมนู", ["Shift+G"]),
    ("auto_toggle", "เปิด/ปิด เข้าท่องเอง (หน้า Decks)", ["P"]),
    ("plus", "ในเมนู: อีก 1 รอบ", ["F", "Space"]),
    ("undo", "ในเมนู: ↶ ย้อน", ["Z"]),
    ("reset", "ในเมนู: ↺ เริ่มใหม่ชุดนี้", ["R"]),
    ("reset_all", "ในเมนู: เริ่มใหม่ทุกชุด", ["Shift+R"]),
    ("study", "ในเมนู (หน้า Decks): ท่องชุดหลัก", ["S"]),
    ("eye", "ในเมนู: ลูกตา (กดค้าง)", ["E"]),
]
DEFAULTS = {c: d for c, _l, d in COMMANDS}
_SPECIAL = {
    Qt.Key.Key_Space: "Space", Qt.Key.Key_Return: "Enter", Qt.Key.Key_Enter: "Enter",
    Qt.Key.Key_Tab: "Tab",
}


def get(cmd):
    v = (cfg().get(CFG) or {}).get(cmd)
    return list(v) if isinstance(v, list) else list(DEFAULTS.get(cmd, []))


def text(cmd):
    return " หรือ ".join(get(cmd)) or "—"


def name(ev):
    """ชื่อปุ่มที่กด เช่น "F", "Shift+R", "Space" (ตำแหน่งปุ่ม ไม่สนภาษาแป้น) · ไม่รู้จัก = "" """
    from . import keymap
    k = ev.key()
    base = _SPECIAL.get(k)
    if base is None and Qt.Key.Key_F1 <= k <= Qt.Key.Key_F12:
        base = "F%d" % (k - Qt.Key.Key_F1 + 1)
    if base is None:
        base = keymap.latin(ev) or ""
    if not base:
        t = ev.text() or ""
        if len(t) == 1 and t.isascii() and t.isprintable() and t != " ":
            base = t
    if not base:
        return ""
    mods = ev.modifiers()
    pre = ""
    if mods & Qt.KeyboardModifier.ShiftModifier and (base.isalnum() or base in _SPECIAL.values()):
        pre = "Shift+"
    return pre + base


def match(ev, cmd):
    if ev.modifiers() & (Qt.KeyboardModifier.ControlModifier | Qt.KeyboardModifier.AltModifier
                         | Qt.KeyboardModifier.MetaModifier):
        return False
    n = name(ev)
    return bool(n) and n in get(cmd)


# ---------------------------------------------------------------- หน้าตั้งค่า

class _KeyEdit(QLineEdit):
    """คลิกแล้วกดปุ่มที่ต้องการ · Backspace/Delete = ลบ"""

    def __init__(self, value=""):
        super().__init__(value)
        self.setReadOnly(True)
        self.setPlaceholderText("คลิกแล้วกดปุ่ม")
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setFixedWidth(120)

    def keyPressEvent(self, ev):
        if ev.key() in (Qt.Key.Key_Backspace, Qt.Key.Key_Delete):
            self.setText("")
            return
        if ev.key() in (Qt.Key.Key_Shift, Qt.Key.Key_Control, Qt.Key.Key_Alt, Qt.Key.Key_Meta,
                        Qt.Key.Key_Escape):
            return
        n = name(ev)
        if n:
            self.setText(n)
            self.focusNextChild()


class ShortcutDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent or mw)
        self.setWindowTitle("CardKit · ตั้งคีย์ลัด")
        lay = QVBoxLayout(self)
        tip = QLabel("คลิกช่องแล้วกดปุ่มที่ต้องการ (ใช้ได้ทั้งแป้นไทย/อังกฤษ) · Backspace = ลบ")
        tip.setStyleSheet("color: gray;")
        lay.addWidget(tip)
        grid = QGridLayout()
        self.edits = {}
        for r, (cid, label, _d) in enumerate(COMMANDS):
            grid.addWidget(QLabel(label), r, 0)
            cur = get(cid) + ["", ""]
            a, b = _KeyEdit(cur[0]), _KeyEdit(cur[1])
            grid.addWidget(a, r, 1)
            grid.addWidget(b, r, 2)
            self.edits[cid] = (a, b)
        lay.addLayout(grid)
        fixed = QLabel("ใช้ได้เสมอ: Enter = อีก 1 รอบ · Esc = ปิดเมนู · 1–9 = ชุดอื่น")
        fixed.setStyleSheet("color: gray; font-size: 11px;")
        lay.addWidget(fixed)
        row = QHBoxLayout()
        rst = QPushButton("คืนค่าเดิม")
        rst.clicked.connect(self._reset)
        row.addWidget(rst)
        row.addStretch(1)
        bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        row.addWidget(bb)
        lay.addLayout(row)

    def _reset(self):
        for cid, (a, b) in self.edits.items():
            d = DEFAULTS[cid] + ["", ""]
            a.setText(d[0])
            b.setText(d[1])

    def accept(self):
        new = {cid: [k for k in (a.text(), b.text()) if k] for cid, (a, b) in self.edits.items()}
        # ปุ่มซ้ำ: เมนู/นอกเมนู แยกกัน (คำสั่ง "ในเมนู" ซ้ำกันเองไม่ได้ · นอกเมนูซ้ำกันเองไม่ได้)
        outside = {"menu", "plus_now", "auto_toggle"}
        for group in (outside, set(new) - outside):
            seen = {}
            for cid in group:
                for k in new[cid]:
                    if k in seen and seen[k] != cid:
                        lab = dict((c, l) for c, l, _d in COMMANDS)
                        showWarning(f"ปุ่ม {k} ซ้ำกัน:\n• {lab[seen[k]]}\n• {lab[cid]}", parent=self)
                        return
                    seen[k] = cid
        inside = set(new) - outside
        for k in (x for c in inside for x in new[c]):
            if k in ("Enter", "Escape") or (len(k) == 1 and k.isdigit()):
                showWarning(f"ปุ่ม {k} ใช้ในเมนูอยู่แล้ว เลือกปุ่มอื่นนะครับ", parent=self)
                return
        c = cfg()
        c[CFG] = new
        mw.addonManager.writeConfig(ADDON, c)
        try:
            from . import bubble
            bubble.reload_shortcuts()
        except Exception:
            pass
        super().accept()
        tooltip("บันทึกคีย์ลัดแล้ว")


def open_dialog(parent=None):
    ShortcutDialog(parent).exec()
