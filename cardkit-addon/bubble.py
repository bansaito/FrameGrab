"""
ปุ่มลอย Rounds (แบบเดียวกับแอปมือถือ AnkiRounds)
------------------------------------------------
  • ปุ่มวงกลมลอยบนหน้าต่างหลัก แสดงเฉพาะหน้ารายการ deck และหน้าก่อนท่อง/Congratulations
  • 2 สถานะ: 📚 น้ำเงิน = รอบนี้ยังท่องไม่ครบ · ➕ เขียว = ครบรอบแล้ว · วงแหวน = ความคืบหน้ารอบ
  • จางลงเองเมื่อไม่ได้ใช้ · ชี้เมาส์/แตะ = ชัด · ลากย้ายได้ (จำตำแหน่ง)
  • คลิก = เมนู: ชุดหลัก (ชุดของ deck ที่ท่องล่าสุด → ไม่งั้นชุดที่ใช้ล่าสุด)
      [➕ อีก 1 รอบ] [↶ ย้อน / ↶ ยกเลิก] [↺ เริ่มใหม่]  👁 (กดค้าง = new · due)
      ชิปชื่อชุดอื่น (คลิก = สลับ) · ⚙️ ✕
"""

from aqt import gui_hooks, mw
from aqt.qt import (
    QColor, QEvent, QFrame, QHBoxLayout, QLabel, QPainter, QPen, QPoint, QPropertyAnimation, QPushButton,
    QRectF, QScrollArea, Qt, QTimer, QVBoxLayout, QWidget,
)

from . import rounds as R

ON_KEY = "rounds_bubble"              # เปิด/ปิดปุ่มลอย (ค่าเริ่มต้น: เปิด)
POS_KEY = "rounds_bubble_pos"         # [x, y] ในหน้าต่างหลัก
LAST_KEY = "rounds_last_used"         # ชื่อชุดที่ใช้ล่าสุด
EDGE_KEY = "rounds_bubble_edge"       # ชิดขอบ ซ่อนครึ่งหนึ่งเมื่อไม่ได้ใช้ (ค่าเริ่มต้น: เปิด)
SIZE = 44
BLUE, GREEN = QColor("#2563EB"), QColor("#16A34A")
FADED = 0.35


def enabled():
    return bool(R._cfg().get(ON_KEY, True))


def edge_mode():
    return bool(R._cfg().get(EDGE_KEY, True))


def _set_cfg(key, val):
    c = R._cfg()
    c[key] = val
    mw.addonManager.writeConfig(R.ADDON, c)


# ---------------------------------------------------------------- data

def primary_group():
    """ชุดของ deck ที่ท่องล่าสุด (deck ที่เลือกอยู่) → ชุดที่ใช้ล่าสุด → ชุดแรก"""
    gs = R.groups()
    if not gs:
        return None
    g = R.group_for_current()
    if g is not None:
        return g
    last = R._cfg().get(LAST_KEY)
    return next((x for x in gs if x["name"] == last), gs[0])


def _live(g):
    return [d for d in g["decks"] if R._deck_name(d["did"])]


def capped_left(g):
    """การ์ดที่ยังท่องได้ในรอบนี้ (ตามเพดานปัจจุบัน แบบเลขที่ Anki แสดง)"""
    tree = mw.col.sched.deck_due_tree()
    n, seen = 0, set()
    for d in _live(g):
        if d["did"] in seen:
            continue
        seen.add(d["did"])
        node = mw.col.decks.find_deck_in_tree(tree, d["did"])
        if node is not None:
            n += node.review_count + node.learn_count + node.new_count
    return n


def undo(g):
    """↶ ย้อน 1 รอบ: เพดาน − ค่าเพิ่ม (ไม่ต่ำกว่าค่าเริ่ม)"""
    R._apply([(d["did"], max(int(d["base"]), R._limit(d["did"]) - int(d["step"]))) for d in _live(g)])
    R._after_change()
    R.tooltip(f"ย้อนแล้ว · {R.summary(g)}")


# ---------------------------------------------------------------- bubble

class Bubble(QWidget):
    def __init__(self):
        super().__init__(mw)
        self.setFixedSize(SIZE, SIZE)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setToolTip("📚 Rounds · คลิก = เมนู · ลากเพื่อย้าย")
        self.done = False
        self.progress = -1.0
        self.round_total = 0
        self.opacity = 1.0
        self._press = None
        self._dragged = False
        self.menu = None
        self._fade = QTimer(self)
        self._fade.setSingleShot(True)
        self._fade.timeout.connect(self.rest)
        self._tucked = False
        self._anim = QPropertyAnimation(self, b"pos", self)
        self._anim.setDuration(180)
        mw.installEventFilter(self)

    def menu_open(self):
        """เมนูยังเปิดอยู่ไหม (เมนูถูกลบทิ้งตอนปิด → เช็กอย่างปลอดภัย)"""
        m = self.menu
        if m is None:
            return False
        try:
            return m.isVisible()
        except RuntimeError:          # ถูกลบไปแล้ว
            self.menu = None
            return False

    # ---- look
    def _set_opacity(self, v):
        if self.menu_open():
            v = 1.0
        self.opacity = v
        self.update()

    def wake(self, ms=1500):
        self._set_opacity(1.0)
        self.tuck(False)
        self._fade.start(ms)

    def rest(self):
        """จาง + ซ่อนครึ่งหนึ่งที่ขอบ (ถ้าเปิดโหมดชิดขอบ)"""
        if self.menu_open() or self._press:
            return
        self._set_opacity(FADED)
        self.tuck(True)

    def paintEvent(self, _ev):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setOpacity(self.opacity)
        r = QRectF(2, 2, SIZE - 4, SIZE - 4)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(GREEN if self.done else BLUE)
        p.drawEllipse(r)
        if self.progress >= 0 and not self.done:
            pen = QPen(QColor(255, 255, 255, 70), 3)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(r.adjusted(2, 2, -2, -2))
            pen.setColor(QColor("white"))
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            p.setPen(pen)
            p.drawArc(r.adjusted(2, 2, -2, -2), 90 * 16, int(-360 * 16 * self.progress))
        p.setPen(QColor("white"))
        f = p.font()
        f.setPointSize(14)
        p.setFont(f)
        p.drawText(r, Qt.AlignmentFlag.AlignCenter, "➕" if self.done else "📚")
        p.end()

    # ---- state
    def refresh(self):
        if mw.col is None:
            return
        g = primary_group()
        if g is None:
            self.done, self.progress = False, -1.0
        else:
            left = capped_left(g)
            if left > self.round_total or self.round_total == 0:
                self.round_total = left
            self.done = left == 0
            self.progress = (1 - left / self.round_total) if self.round_total else -1.0
        self.update()

    # ---- position
    def _clamp(self, pt):
        x = max(0, min(pt.x(), mw.width() - SIZE))
        y = max(30, min(pt.y(), mw.height() - SIZE))
        return QPoint(x, y)

    def home(self):
        """ตำแหน่งตอนแสดงเต็ม (โหมดชิดขอบ: ติดขอบซ้ายหรือขวาของหน้าต่าง)"""
        pos = R._cfg().get(POS_KEY)
        pt = self._clamp(QPoint(*pos) if pos else QPoint(mw.width() - SIZE - 24, mw.height() // 2))
        if edge_mode():
            pt.setX(0 if pt.x() + SIZE / 2 < mw.width() / 2 else mw.width() - SIZE)
        return pt

    def tuck(self, on, animate=True):
        on = bool(on) and edge_mode()
        target = self.home()
        if on:
            target.setX(-SIZE // 2 if target.x() == 0 else mw.width() - SIZE // 2)
        self._tucked = on
        self._anim.stop()
        if animate and self.isVisible():
            self._anim.setStartValue(self.pos())
            self._anim.setEndValue(target)
            self._anim.start()
        else:
            self.move(target)

    def place(self, tucked=False):
        self.tuck(tucked, animate=False)
        self.raise_()

    def eventFilter(self, obj, ev):
        if obj is mw and ev.type() == QEvent.Type.Resize and self.isVisible():
            self.tuck(self._tucked, animate=False)
        return False

    # ---- mouse
    def enterEvent(self, _ev):
        self._fade.stop()
        self._set_opacity(1.0)
        self.tuck(False)

    def leaveEvent(self, _ev):
        self._fade.start(1500)

    def _gp(self, ev):
        try:
            return ev.globalPosition().toPoint()
        except AttributeError:
            return ev.globalPos()

    def mousePressEvent(self, ev):
        if ev.button() == Qt.MouseButton.LeftButton:
            self._press = (self._gp(ev), self.pos())
            self._dragged = False

    def mouseMoveEvent(self, ev):
        if not self._press:
            return
        start, origin = self._press
        delta = self._gp(ev) - start
        if not self._dragged and delta.manhattanLength() > 4:
            self._dragged = True
        if self._dragged:
            self._anim.stop()
            self.move(self._clamp(origin + delta))

    def mouseReleaseEvent(self, ev):
        if not self._press:
            return
        self._press = None
        if self._dragged:
            _set_cfg(POS_KEY, [self.x(), self.y()])
            self.tuck(False)                 # โหมดชิดขอบ: ดีดไปติดขอบที่ใกล้สุด
            self._fade.start(1500)
            return
        self.toggle_menu()

    def _menu_gone(self, *_a):
        self.menu = None

    def toggle_menu(self, pick=None):
        if self.menu_open():
            self.menu.close()
            return
        self.menu = Menu(self, pick)
        self.menu.destroyed.connect(self._menu_gone)
        self.menu.show_beside(self)
        self._set_opacity(1.0)


# ---------------------------------------------------------------- menu

_CHIP = ("QPushButton{color:white;background:%s;border:none;border-radius:10px;padding:6px 12px;}"
         "QPushButton:hover{background:%s;}")
_before_reset = {}    # ชื่อชุด → {did: เพดานเดิม}  (ไว้ใช้กับ ↶ ยกเลิก)


def _chip(text, bg="#374151", hover="#4B5563", tip=None):
    b = QPushButton(text)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    b.setStyleSheet(_CHIP % (bg, hover))
    if tip:
        b.setToolTip(tip)
    return b


class Menu(QFrame):
    def __init__(self, bubble, pick=None):
        super().__init__(mw, Qt.WindowType.Popup | Qt.WindowType.FramelessWindowHint)
        self.bubble = bubble
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
        self.setStyleSheet("Menu{background:#1E222A;border:1px solid #333;border-radius:14px;}"
                           "QLabel{color:#B0B6C0;}")
        gs = R.groups()
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 10, 12, 10)
        if not gs:
            lay.addWidget(QLabel("ยังไม่มีชุดท่อง"))
            b = _chip("⚙️ ตั้งค่าชุดท่อง…")
            b.clicked.connect(lambda: (self.close(), R.open_settings()))
            lay.addWidget(b)
            return
        g = next((x for x in gs if pick and x["name"] == pick), None) or primary_group()
        self.g = g

        # ชื่อชุด ……… 👁
        head = QHBoxLayout()
        name = QLabel(g["name"])
        head.addWidget(name, 1)
        eye = QPushButton()
        eye.setIcon(R._outline_icon("eye", QColor("#E5E7EB")))
        eye.setFlat(True)
        eye.setCursor(Qt.CursorShape.PointingHandCursor)
        eye.setStyleSheet("QPushButton{border:none;padding:2px 6px;}")
        _fx = __import__("aqt.qt", fromlist=["QGraphicsOpacityEffect"]).QGraphicsOpacityEffect(eye)
        _fx.setOpacity(0.4)
        eye.setGraphicsEffect(_fx)
        eye.pressed.connect(lambda: _fx.setOpacity(1.0))
        eye.released.connect(lambda: _fx.setOpacity(0.4))
        eye.setToolTip("กดค้าง = ดู new · due ทั้งหมด (วันนี้ + ค้าง) · คีย์ลัด: กดค้าง E")
        self._eye, self._eye_fx = eye, _fx
        eye.pressed.connect(lambda: self._peek(eye, True))
        eye.released.connect(lambda: self._peek(eye, False))
        head.addWidget(eye)
        lay.addLayout(head)
        self._peek_label = None

        # ปุ่มหลัก
        row = QHBoxLayout()
        row.setSpacing(6)
        plus = _chip("➕ อีก 1 รอบ", "#2563EB", "#3B82F6", "อีก 1 รอบ (Enter) · หน้าแรกกด S = ท่องชุดนี้")
        self._plus, self._undo_btn = plus, None
        plus.clicked.connect(lambda: self._do(lambda: R.bump(g)))
        row.addWidget(plus)
        if g["name"] in _before_reset:
            b = _chip("↶ ยกเลิก", tip="ยกเลิกการเริ่มใหม่ (กลับเป็นค่าก่อนกด ↺) · Z")
            b.clicked.connect(lambda: self._do(self._restore))
            row.addWidget(b)
            self._undo_btn = b
        elif R.round_of(g) > 1:
            b = _chip("↶ ย้อน", tip="ย้อน 1 รอบ (Z)")
            b.clicked.connect(lambda: self._do(lambda: undo(g)))
            row.addWidget(b)
            self._undo_btn = b
        rst = _chip("↺ เริ่มใหม่", tip="กลับเป็นค่าเริ่มต้น " + "/".join(str(d["base"]) for d in g["decks"]) + " (R)")
        self._rst = rst
        rst.clicked.connect(lambda: self._do(self._reset))
        row.addWidget(rst)
        row.addStretch(1)
        lay.addLayout(row)

        # ชุดอื่น: ชิปเล็ก (คลิก = สลับ)
        others = [x for x in gs if x["name"] != g["name"]]
        if others:
            line = QFrame()
            line.setFixedHeight(1)
            line.setStyleSheet("background:#33FFFFFF;")
            lay.addWidget(line)
            strip_w = QWidget()
            strip = QHBoxLayout(strip_w)
            strip.setContentsMargins(0, 0, 0, 0)
            strip.setSpacing(6)
            self._others = [o["name"] for o in others]
            for k, o in enumerate(others, 1):
                c = QPushButton()
                c.setCursor(Qt.CursorShape.PointingHandCursor)
                c.setToolTip((f"สลับไปชุด {o['name']} · กด {k} ที่หน้าแรก = เข้าไปท่องชุดนี้เลย")
                             if k <= 9 else f"สลับไปชุด {o['name']}")
                c.setStyleSheet("QPushButton{background:#2A2F38;border:none;border-radius:8px;}"
                                "QPushButton:hover{background:#374151;}")
                hl = QHBoxLayout(c)
                hl.setContentsMargins(10, 3, 10, 3)
                hl.setSpacing(6)
                nm = QLabel(o["name"])
                nm.setStyleSheet("color:#D1D5DB;font-size:12px;background:transparent;")
                hl.addWidget(nm)
                if k <= 9:
                    kn = QLabel(str(k))                      # เลขคีย์ลัดจาง ๆ
                    kn.setStyleSheet("color:#6B7280;font-size:10px;background:transparent;")
                    hl.addWidget(kn)
                for w in c.findChildren(QLabel):
                    w.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
                c.setMinimumSize(hl.sizeHint())
                c.clicked.connect(lambda _=False, n=o["name"]: self._switch(n))
                strip.addWidget(c)
            strip.addStretch(1)
            sc = QScrollArea()
            sc.setWidget(strip_w)
            sc.setWidgetResizable(True)
            sc.setFrameShape(QFrame.Shape.NoFrame)
            sc.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
            sc.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            sc.setStyleSheet("background:transparent;")
            sc.setFixedHeight(strip_w.sizeHint().height() + 4)
            lay.addWidget(sc)

        bottom = QHBoxLayout()
        gear = _chip("⚙️", "#2A2F38", "#374151", "ตั้งค่าชุดท่อง")
        gear.clicked.connect(lambda: (self.close(), R.open_settings()))
        close = _chip("✕", "#2A2F38", "#374151", "ปิด (Esc)")
        close.clicked.connect(self.close)
        bottom.addStretch(1)
        if R.can_undo_all():
            allb = _chip("↶ ทุกชุด", "#2A2F38", "#374151", "ยกเลิกการเริ่มใหม่ทุกชุด (กลับเป็นค่าก่อนกด) · Shift+R")
            allb.clicked.connect(lambda: self._do_all(R.undo_reset_all))
        else:
            allb = _chip("↺", "#2A2F38", "#374151", "เริ่มใหม่ทุกชุด: ทุกชุดกลับเป็นค่าเริ่มต้น เช่น 2/1/1 (Shift+R)")
            allb.clicked.connect(lambda: self._do_all(R.reset_all))
        bottom.addWidget(allb)
        bottom.addWidget(gear)
        bottom.addWidget(close)
        lay.addLayout(bottom)
        self._allb = allb
        # คีย์ลัดทำงานที่ตัวเมนู: ปุ่มไม่รับโฟกัส (กัน Enter ไปกดปุ่มที่โฟกัสอยู่)
        for w in self.findChildren(QPushButton):
            w.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

    # ---- คีย์ลัดตอนเมนูเปิด
    def keyPressEvent(self, ev):
        from . import keymap
        k, mod = ev.key(), ev.modifiers()
        K = Qt.Key
        shift = bool(mod & Qt.KeyboardModifier.ShiftModifier)
        L = keymap.latin(ev)                     # ตำแหน่งปุ่ม → ใช้ได้ทั้งแป้นไทย/อังกฤษ
        if k in (K.Key_Return, K.Key_Enter) or ev.text() in ("+", "="):
            self._plus.click()
        elif L == "Z" and self._undo_btn is not None:
            self._undo_btn.click()
        elif L == "R" and shift:
            self._allb.click()
        elif L == "R":
            self._rst.click()
        elif L and L.isdigit() and L != "0" and getattr(self, "_others", None):
            i = int(L) - 1
            if i < len(self._others):
                if mw.state == "deckBrowser":          # หน้าแรก: กดเลข = เข้าไปท่องชุดนั้นเลย
                    self._study(self._others[i])
                else:                                   # หน้าอื่น: สลับดูชุด
                    self._switch(self._others[i])
        elif L == "S" and mw.state == "deckBrowser":
            self._study(self.g["name"])                 # S = ท่องชุดหลัก (ชุดที่แสดงอยู่)
        elif L == "G":
            self.close()                                # G อีกครั้ง = ปิด
        elif L == "E":
            if not ev.isAutoRepeat():
                self._eye_fx.setOpacity(1.0)
                self._peek(self._eye, True)
        elif k == K.Key_Escape:
            self.close()
        else:
            super().keyPressEvent(ev)

    def keyReleaseEvent(self, ev):
        from . import keymap
        if keymap.latin(ev) == "E" and not ev.isAutoRepeat():
            self._eye_fx.setOpacity(0.4)
            self._peek(self._eye, False)
        else:
            super().keyReleaseEvent(ev)

    # ---- actions (ทำทันที ไม่มีการกดแทนเหมือนในมือถือ)
    def _do(self, fn):
        self.close()
        _set_cfg(LAST_KEY, self.g["name"])
        if fn != self._reset and fn != self._restore:
            _before_reset.pop(self.g["name"], None)       # กด ➕/↶ แล้ว ยกเลิกเริ่มใหม่ไม่ได้อีก
        fn()
        self.bubble.round_total = 0
        refresh()

    def _do_all(self, fn):
        self.close()
        _before_reset.clear()                 # ยกเลิกทีละชุดใช้ไม่ได้แล้วหลังทำทุกชุด
        fn()
        self.bubble.round_total = 0
        refresh()

    def _reset(self):
        _before_reset[self.g["name"]] = {d["did"]: R._limit(d["did"]) for d in _live(self.g)}
        R.reset(self.g)

    def _restore(self):
        prev = _before_reset.pop(self.g["name"], None)
        if not prev:
            return
        R._apply(list(prev.items()))
        R._after_change()
        R.tooltip(f"ยกเลิกการเริ่มใหม่แล้ว · {R.summary(self.g)}")

    def _study(self, name):
        g = next((x for x in R.groups() if x["name"] == name), None)
        self.close()
        if g is not None:
            _set_cfg(LAST_KEY, name)
            QTimer.singleShot(0, lambda: R.study_set(g))

    def _switch(self, name):
        b = self.bubble
        self.close()
        QTimer.singleShot(0, lambda: b.toggle_menu(pick=name))

    def _peek(self, eye, show):
        if not show:
            if self._peek_label is not None:
                self._peek_label.close()
                self._peek_label = None
            return
        lab = QLabel(_peek_html(self.g), None, Qt.WindowType.ToolTip)
        lab.setStyleSheet("QLabel{background:#111318;color:white;border-radius:8px;padding:6px 10px;}")
        lab.adjustSize()
        gp = eye.mapToGlobal(QPoint(0, 0))
        lab.move(gp.x() - lab.width() + eye.width(), gp.y() - lab.height() - 6)
        lab.show()
        self._peek_label = lab

    def show_beside(self, bubble):
        self.adjustSize()
        bp = bubble.mapToGlobal(QPoint(0, 0))
        scr = bubble.screen().availableGeometry()
        x = bp.x() + SIZE + 8
        if x + self.width() > scr.right():
            x = bp.x() - self.width() - 8
        y = min(bp.y(), scr.bottom() - self.height())
        self.move(max(scr.left(), x), max(scr.top(), y))
        self.show()
        self.activateWindow()
        self.setFocus()

    def closeEvent(self, ev):
        self._peek(None, False)
        self.bubble.wake()
        super().closeEvent(ev)


def _peek_html(g):
    """new (น้ำเงิน) · due ทั้งหมด = วันนี้ + ค้าง + learn (เขียว)"""
    import html as _h
    cn, _cl, cr = R._state_colors()
    try:
        rows = R.due_counts(g)
    except Exception as e:
        return f"นับไม่ได้: {e}"

    def num(v, c):
        return f"<b style='color:{c if v else '#6B7280'}'>{v}</b>"

    out = ["<span style='color:#9CA3AF'>new · due (วันนี้ + ค้าง)</span>"]
    tn = td = 0
    for name, new, learn, due in rows:
        tn += new
        td += learn + due
        out.append(f"{_h.escape(name)}&nbsp;&nbsp;{num(new, cn)}&nbsp;&nbsp;{num(learn + due, cr)}")
    if len(rows) > 1:
        out.append(f"รวม&nbsp;&nbsp;{num(tn, cn)}&nbsp;&nbsp;{num(td, cr)}")
    return "<br>".join(out)


# ---------------------------------------------------------------- wiring

_bubble = None
_timer = None


def _should_show():
    return enabled() and mw.col is not None and mw.state in ("deckBrowser", "overview")


def refresh(*_args):
    global _bubble
    if not _should_show():
        if _bubble is not None:
            _bubble.hide()
            if _bubble.menu_open():
                _bubble.menu.close()
        return
    if _bubble is None:
        _bubble = Bubble()
        _bubble.place()
        _bubble.show()
        _bubble.wake(2500)      # เพิ่งเปิดโปรแกรม: ชัดครั้งแรก แล้วจาง
    elif not _bubble.isVisible():
        _bubble.place(tucked=True)
        _bubble.show()
        _bubble._set_opacity(FADED)   # กลับมาหน้าแรกหลังท่อง: จาง + ซ่อนที่ขอบ ไม่เด้ง
    _bubble.raise_()
    try:
        _bubble.refresh()
    except Exception as e:
        print("[rounds bubble]", e)


def refresh_soon(*_args):
    """รวมการเรียกถี่ ๆ (เช่นหลัง sync) เป็นครั้งเดียว"""
    global _timer
    if _timer is None:
        _timer = QTimer(mw)
        _timer.setSingleShot(True)
        _timer.timeout.connect(refresh)
    _timer.start(300)


# ---------------------------------------------------------------- คีย์ลัด G = เปิด/ปิดเมนูปุ่มลอย

_g_shortcut = None


def _on_g():
    if not _should_show() or _bubble is None:
        return
    _bubble._fade.stop()
    _bubble._set_opacity(1.0)
    _bubble.tuck(False, animate=False)
    _bubble.toggle_menu()


def _setup_g_shortcut():
    """G ใช้ได้เฉพาะหน้ารายการ deck / หน้าก่อนท่อง (หน้าอื่นปล่อยให้ Anki ใช้ตามปกติ)"""
    global _g_shortcut
    from aqt.qt import QShortcut, QKeySequence
    if _g_shortcut is None:
        from . import keymap
        _g_shortcut = QShortcut(QKeySequence("G"), mw)
        _g_shortcut.activated.connect(_on_g)
        keymap.register(["deckBrowser", "overview"], "G", _on_g)   # แป้นไทย: ปุ่ม เ ก็ได้
    _g_shortcut.setEnabled(_should_show())


def set_edge(on):
    _set_cfg(EDGE_KEY, bool(on))
    if _bubble is not None:
        _bubble.wake()


def set_enabled(on):
    _set_cfg(ON_KEY, bool(on))
    refresh()
    try:
        _setup_g_shortcut()
    except Exception:
        pass


def _on_state(new_state, _old_state):
    refresh_soon()
    try:
        _setup_g_shortcut()
    except Exception as e:
        print("[rounds bubble] G", e)


gui_hooks.state_did_change.append(_on_state)
gui_hooks.operation_did_execute.append(lambda *_a: refresh_soon())
gui_hooks.profile_did_open.append(refresh_soon)
