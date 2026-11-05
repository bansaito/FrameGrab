"""
ท่องเป็นรอบ (Rounds)
--------------------
จัด deck เป็น "ชุด" (ชุดละไม่เกิน 3 deck) แต่ละ deck มี
  base – ค่าเริ่มต้นของ Maximum reviews/day
  step – เพิ่มครั้งละเท่าไรเมื่อกด "อีก 1 รอบ"

  • ➕ อีก 1 รอบ   – เพดานของทุก deck ในชุด = ที่ท่องไปแล้ววันนี้ + step
                     ค่าเริ่มต้น: ปรับช่อง Preset (ค้างไว้จนกด ↺ เริ่มชุดใหม่)
                     สวิตช์: ปรับช่อง Today only (หมดอายุเองตี 4 ทุกเครื่อง)
  • ↺ เริ่มชุดใหม่ – ปุ่มเล็กท้ายแถวของชุดในเมนู · Preset กลับเป็น base และล้าง Today only
  • ปุ่มในหน้าท่องครบบอกเมื่อทั้งชุดไม่มีการ์ดเหลือ / แสดงจำนวนที่เหลือ (เปิดปิดได้)
"""

from aqt import gui_hooks, mw
from aqt.qt import (
    QAbstractItemView, QDialog, QDialogButtonBox, QFormLayout, QGroupBox, QHBoxLayout,
    QKeySequence, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMenu, QPushButton,
    QSpinBox, Qt, QTimer, QToolButton, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget,
    QWidgetAction,
)
from aqt.utils import askUser, showInfo, tooltip

ADDON = __name__.split(".")[0]
KEY = "rounds_groups"
SHOW_REMAINING = "rounds_show_remaining"     # แสดงจำนวนที่เหลือบนปุ่ม (ค่าเริ่มต้น: ปิด)
MODE_KEY = "rounds_use_today_only"           # False = ปรับช่อง Preset + รีเซ็ตเอง (ค่าเริ่มต้น)
                                             # True  = ปรับช่อง Today only + หมดอายุเองตี 4
BROWSE_NEW = "rounds_browse_new"             # 🔍 ดูการ์ดที่เหลือ: รวม new ด้วย (ค่าเริ่มต้น: ปิด = due อย่างเดียว)
CUR_FIRST = "rounds_current_first"           # ชุดของ deck ที่เลือกอยู่ขึ้นบนสุดในเมนู (ค่าเริ่มต้น: เปิด)
FOLD_KEY = "rounds_congrats_folded"         # deck แม่ที่พับไว้ในตารางหน้าท่องเสร็จ
MAX_DECKS = 3


# ---------------------------------------------------------------- storage

def _cfg():
    try:
        return mw.addonManager.getConfig(ADDON) or {}
    except Exception:
        return {}


def groups():
    return [g for g in _cfg().get(KEY, []) if g.get("decks")]


def save_groups(gs):
    c = _cfg()
    c[KEY] = gs
    mw.addonManager.writeConfig(ADDON, c)


def show_remaining():
    return bool(_cfg().get(SHOW_REMAINING, False))


def set_show_remaining(on):
    c = _cfg()
    c[SHOW_REMAINING] = bool(on)
    mw.addonManager.writeConfig(ADDON, c)
    if mw.state == "overview":
        mw.moveToState("overview")


# ---------------------------------------------------------------- deck helpers

def _deck(did):
    return mw.col.decks.get(did, default=False)


def _deck_name(did):
    d = _deck(did)
    return d["name"] if d else None


def _short(did):
    return (_deck_name(did) or "?").split("::")[-1]


def _conf(did):
    return mw.col.decks.config_dict_for_deck_id(did)


def _preset(did):
    return int(_conf(did)["rev"]["perDay"])


def _limit(did):
    """เพดาน reviews/day ที่ใช้อยู่จริงวันนี้: Today only > This deck > Preset"""
    d = _deck(did)
    today = mw.col.sched.today
    t = d.get("reviewLimitToday") if d else None
    if t and t.get("today") == today:
        return int(t.get("limit", 0))
    if d and d.get("reviewLimit") is not None:
        return int(d["reviewLimit"])
    return _preset(did)


def _set_today(did, val):
    d = _deck(did)
    d["reviewLimitToday"] = {"limit": int(val), "today": mw.col.sched.today}
    mw.col.decks.save(d)


def _clear_today(did):
    d = _deck(did)
    if d.get("reviewLimitToday") is not None:
        d["reviewLimitToday"] = None
        mw.col.decks.save(d)


def _ensure_preset(did, base):
    """ช่อง Preset = ค่าเริ่มต้นของชุด (ทุกวันใหม่จะเริ่มจากค่านี้)"""
    conf = _conf(did)
    if int(conf["rev"]["perDay"]) != int(base):
        conf["rev"]["perDay"] = int(base)
        mw.col.decks.update_config(conf)


def _shared_presets(g):
    """preset ที่ใช้ร่วมกันแต่ตั้ง "เริ่ม/เพิ่มครั้งละ" ไม่เท่ากัน (ขัดกันเอง)"""
    seen, clash = {}, set()
    for d in g["decks"]:
        if not _deck_name(d["did"]):
            continue
        cid = _conf(d["did"])["id"]
        pair = (int(d.get("base", 0)), int(d.get("step", 0)))
        if cid in seen and seen[cid] != pair:
            clash.add(cid)
        seen.setdefault(cid, pair)
    return clash


def done_today(did):
    """จำนวนการ์ดที่ท่องไปแล้ววันนี้ใน deck นี้ (รวมที่ท่องในโทรศัพท์แล้ว sync มา)
    นับทั้ง review และการ์ดใหม่ เพราะ Anki นับการ์ดใหม่เข้าเพดาน reviews/day ด้วย
    (ยกเว้นเปิด "New cards ignore review limit")"""
    d = _deck(did)
    if not d:
        return 0
    today = mw.col.sched.today
    rev = d.get("revToday", [0, 0])
    new = d.get("newToday", [0, 0])
    n = rev[1] if rev and rev[0] == today else 0
    if not _ignore_new(did) and new and new[0] == today:
        n += new[1]
    return int(n)


def _ignore_new(did):
    try:
        return bool(mw.col.decks.get_deck_configs_for_update(did).new_cards_ignore_review_limit)
    except Exception:
        return False


def due_counts(g):
    """[(ชื่อสั้น, new ที่ท่องได้วันนี้, learn, due ทั้งหมด = ถึงวันนี้ + ค้าง)] ของแต่ละ deck (รวม deck ย่อย)
    ไม่สนเพดาน reviews/day · ไม่นับการ์ดที่ suspend/bury"""
    from anki.collection import SearchNode
    today = mw.col.sched.today
    out = []
    for d in g["decks"]:
        did = d["did"]
        deck = _deck(did)
        if not deck:
            continue
        dq = mw.col.build_search_string(SearchNode(deck=deck["name"])) + " -is:suspended -is:buried"

        def n(q):
            return len(mw.col.find_cards(f"{dq} {q}"))

        learn = n("is:learn is:due")
        due = n("is:review -is:learn prop:due<=0")      # ถึงวันนี้ + ค้างจากวันก่อน
        t = deck.get("newLimitToday")
        if t and t.get("today") == today:
            cap = int(t.get("limit", 0))
        elif deck.get("newLimit") is not None:
            cap = int(deck["newLimit"])
        else:
            cap = int(_conf(did)["new"]["perDay"])
        nt = deck.get("newToday", [0, 0])
        done = nt[1] if nt and nt[0] == today else 0
        new = max(0, min(n("is:new"), cap - done))
        out.append((_short(did), int(new), learn, due))
    return out


def _state_colors():
    """สีเดียวกับหน้ารายการ deck ของ Anki: new น้ำเงิน · learn แดง · due เขียว"""
    try:
        from aqt import colors
        from aqt.theme import theme_manager
        return (theme_manager.var(colors.STATE_NEW), theme_manager.var(colors.STATE_LEARN),
                theme_manager.var(colors.STATE_REVIEW))
    except Exception:
        return ("#3b82f6", "#ef4444", "#22c55e")


def due_text(g):
    import html as _h
    try:
        rows = due_counts(g)
    except Exception as e:
        return f"นับการ์ดไม่ได้: {e}"
    with_new = bool(_cfg().get(BROWSE_NEW, False))
    cn, cl, cr = _state_colors()
    if not any(r[2] or r[3] or (with_new and r[1]) for r in rows):
        return f"<span style='color:{cr}'>✓ ไม่มี due แล้ว</span>"

    def cell(v, c):
        return f"<td align=right style='padding-left:12px;color:{c if v else '#888'}'>{v}</td>"

    def hcell(t, c):
        return f"<td align=right style='padding-left:12px;color:{c}'>{t}</td>"

    head = ("<td></td>" + (hcell("New", cn) if with_new else "") + hcell("Learn", cl)
            + hcell("Due", cr))
    body = ""
    tot = [0, 0, 0]
    for name, new, learn, due in rows:
        tot = [tot[0] + new, tot[1] + learn, tot[2] + due]
        body += ("<tr><td>" + _h.escape(name) + "</td>" + (cell(new, cn) if with_new else "")
                 + cell(learn, cl) + cell(due, cr) + "</tr>")
    if len(rows) > 1:
        body += ("<tr><td><b>รวม</b></td>" + (cell(tot[0], cn) if with_new else "")
                 + cell(tot[1], cl) + cell(tot[2], cr) + "</tr>")
    return f"<table cellspacing=0>{'<tr>' + head + '</tr>'}{body}</table>"


def remaining(did):
    """การ์ดที่ยังถึงเวลาท่องวันนี้ ไม่นับเพดาน reviews/day
    (การ์ดใหม่นับไม่เกินที่เหลือของ New cards/day)"""
    d = _deck(did)
    if not d:
        return 0
    node = mw.col.decks.find_deck_in_tree(mw.col.sched.deck_due_tree(), did)
    if node is None:
        return 0
    today = mw.col.sched.today
    t = d.get("newLimitToday")
    if t and t.get("today") == today:
        new_cap = int(t.get("limit", 0))
    elif d.get("newLimit") is not None:
        new_cap = int(d["newLimit"])
    else:
        new_cap = int(_conf(did)["new"]["perDay"])
    nt = d.get("newToday", [0, 0])
    new_done = nt[1] if nt and nt[0] == today else 0
    new_left = max(0, min(node.new_uncapped, new_cap - new_done))
    return int(node.review_uncapped + node.interday_learning_uncapped + new_left)


def round_of(g):
    """รอบปัจจุบัน (1 = ค่าเริ่มต้น) คำนวณจาก deck แรกที่ยังมีอยู่"""
    for d in g["decks"]:
        if _deck_name(d["did"]) and d.get("step", 0) > 0:
            return max(1, (_limit(d["did"]) - d["base"]) // d["step"] + 1)
    return 1


def summary(g):
    return " · ".join(f'{_short(d["did"])} {_limit(d["did"])}' for d in g["decks"] if _deck_name(d["did"]))


# ---------------------------------------------------------------- actions

def _after_change():
    if mw.state in ("overview", "deckBrowser"):
        mw.moveToState(mw.state)
    else:
        mw.reset()


def _next_limit(d):
    """เพดานหลังกด "อีก 1 รอบ" = max(เพดานที่ใช้อยู่, ที่ท่องไปแล้ววันนี้) + step"""
    return max(_limit(d["did"]), done_today(d["did"])) + d["step"]


def today_mode():
    return bool(_cfg().get(MODE_KEY, False))


def set_today_mode(on):
    c = _cfg()
    c[MODE_KEY] = bool(on)
    mw.addonManager.writeConfig(ADDON, c)
    tooltip("ปรับช่อง: " + ("Today only (กลับเป็นค่าเริ่มต้นเองตี 4)" if on
                             else "Preset (ค้างไว้จนกด ↺ เริ่มชุดใหม่)"))
    if mw.state == "overview":
        mw.moveToState("overview")


def _apply(targets):
    """targets: [(did, ค่าใหม่)]
    โหมด Preset: ตั้ง Preset (deck ที่ใช้ preset ร่วมกันได้ค่าที่มากที่สุดของกลุ่มนั้น) แล้วล้าง Today only
    โหมด Today only: ตั้ง Today only ของแต่ละ deck"""
    if today_mode():
        for did, val in targets:
            _set_today(did, val)
        return
    by_conf = {}
    for did, val in targets:
        cid = _conf(did)["id"]
        by_conf[cid] = max(val, by_conf.get(cid, 0))
    done = set()
    for did, _val in targets:
        conf = _conf(did)
        if conf["id"] not in done:
            conf["rev"]["perDay"] = int(by_conf[conf["id"]])
            mw.col.decks.update_config(conf)
            done.add(conf["id"])
        _clear_today(did)


def bump(g):
    """เพดานใหม่ = max(เพดานที่ใช้อยู่, ที่ท่องไปแล้ววันนี้) + step
    กดหนึ่งครั้งได้การ์ดเพิ่มอีก step ใบจริงเสมอ ไม่ว่าจะท่องในโทรศัพท์มาก่อนแค่ไหน"""
    if mw.col is None:
        return
    live = [d for d in g["decks"] if _deck_name(d["did"])]
    if live and all(remaining(d["did"]) == 0 for d in live):
        tooltip("✅ วันนี้ไม่มีการ์ดเหลือแล้วในชุดนี้")
        return
    global _before_all
    _before_all = None                       # กด ➕ แล้ว ยกเลิกเริ่มใหม่ทุกชุดไม่ได้อีก
    _apply([(d["did"], _next_limit(d)) for d in live])
    _after_change()
    empty = [_short(d["did"]) for d in live if remaining(d["did"]) == 0]
    tooltip(f"เพดานใหม่ {summary(g)}" + (f" ({', '.join(empty)} หมดแล้ว)" if empty else ""))


def reset(g):
    """↺ เริ่มชุดใหม่: Preset = ค่าเริ่มต้น และล้าง Today only"""
    if mw.col is None:
        return
    for d in g["decks"]:
        if _deck_name(d["did"]):
            _ensure_preset(d["did"], d["base"])
            _clear_today(d["did"])
    _after_change()
    tooltip(f"เริ่มชุดใหม่ \"{g['name']}\" · {summary(g)}")


# ---------------------------------------------------------------- ↺ ทุกชุด

_before_all = None        # {did: เพดานเดิม} ก่อนกด "เริ่มใหม่ทุกชุด" (ไว้ใช้กับ ↶ ยกเลิก)


def can_undo_all():
    return bool(_before_all)


def reset_all():
    """↺ เริ่มใหม่ทุกชุด: ทุก deck ในทุกชุดกลับเป็นค่าเริ่มของชุดนั้น (ชุดที่เป็นค่าเริ่มอยู่แล้วไม่แตะ)"""
    global _before_all
    if mw.col is None:
        return
    before, changed = {}, []
    for g in groups():
        live = [d for d in g["decks"] if _deck_name(d["did"])]
        if all(_limit(d["did"]) == int(d["base"]) for d in live):
            continue
        for d in live:
            before.setdefault(d["did"], _limit(d["did"]))
            _ensure_preset(d["did"], d["base"])
            _clear_today(d["did"])
        changed.append(g["name"])
    if not changed:
        tooltip("ทุกชุดเป็นค่าเริ่มต้นอยู่แล้ว")
        return
    _before_all = before
    _after_change()
    tooltip("เริ่มใหม่ทุกชุดแล้ว: " + ", ".join(changed) + " · กด ↶ ยกเลิก ได้จากเมนู", period=4000)


def undo_reset_all():
    global _before_all
    prev, _before_all = _before_all, None
    if not prev or mw.col is None:
        return
    _apply(list(prev.items()))
    _after_change()
    tooltip("ยกเลิกการเริ่มใหม่ทุกชุดแล้ว")


def align_presets(*_args):
    """โหมด Today only เท่านั้น: เปิดโปรไฟล์แล้วตั้ง Preset = ค่าเริ่มต้น
    (โหมด Preset ไม่แตะ ค่าที่ปรับไว้ค้างจนกว่าจะกด ↺ เริ่มชุดใหม่ เอง)"""
    if mw.col is None or not today_mode():
        return
    for g in groups():
        for d in g["decks"]:
            if _deck_name(d["did"]):
                _ensure_preset(d["did"], d["base"])


def group_for_current():
    if mw.col is None:
        return None
    cur = mw.col.decks.current()
    cur_id, cur_name = cur["id"], cur["name"]
    for g in groups():
        for d in g["decks"]:
            name = _deck_name(d["did"])
            if d["did"] == cur_id or (name and name.startswith(cur_name + "::")):
                return g
    return None


def bump_current():
    g = group_for_current()
    if g is None:
        gs = groups()
        if len(gs) == 1:
            g = gs[0]
        else:
            tooltip("เลือก deck ที่อยู่ในชุดก่อน หรือใช้เมนู 📚 Rounds")
            return
    bump(g)


def button_label(g):
    """ข้อความปุ่มในหน้าท่องครบ — None = ไม่มีการ์ดเหลือแล้ว"""
    live = [d for d in g["decks"] if _deck_name(d["did"])]
    rem = [remaining(d["did"]) for d in live]
    if live and not any(rem):
        return None
    now = [_limit(d["did"]) for d in live]
    nxt = [_next_limit(d) for d in live]
    label = f"➕ อีก 1 รอบ · {'/'.join(map(str, now))} → {'/'.join(map(str, nxt))}"
    if show_remaining():
        label += " · เหลือ " + " / ".join(str(x) for x in rem)
    else:
        empty = [_short(d["did"]) for d, x in zip(live, rem) if x == 0]
        if empty:
            label += f" ({', '.join(empty)} หมดแล้ว)"
    return label


# ---------------------------------------------------------------- settings dialog

class DeckPicker(QDialog):
    """เลือก deck แบบต้นไม้ (deck ย่อยพับไว้) + ช่องค้นหา"""

    def __init__(self, parent, current=None):
        super().__init__(parent)
        self.setWindowTitle("เลือก deck")
        self.resize(420, 520)
        self.did = None
        lay = QVBoxLayout(self)
        self.search = QLineEdit()
        self.search.setPlaceholderText("ค้นหา deck…")
        self.search.setClearButtonEnabled(True)
        lay.addWidget(self.search)
        self.tree = QTreeWidget()
        self.tree.setHeaderHidden(True)
        lay.addWidget(self.tree, 1)
        bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        bb.accepted.connect(self._ok)
        bb.rejected.connect(self.reject)
        lay.addWidget(bb)

        self.items = {}            # ชื่อเต็ม → item
        decks = sorted(mw.col.decks.all_names_and_ids(include_filtered=False, skip_empty_default=True),
                       key=lambda d: d.name.lower())
        for d in decks:
            parts = d.name.split("::")
            parent_item = self.items.get("::".join(parts[:-1])) if len(parts) > 1 else None
            it = QTreeWidgetItem([parts[-1]])
            it.setData(0, Qt.ItemDataRole.UserRole, d.id)
            it.setData(0, Qt.ItemDataRole.UserRole + 1, d.name.lower())
            (parent_item.addChild(it) if parent_item else self.tree.addTopLevelItem(it))
            self.items[d.name] = it
        self.tree.collapseAll()
        self.current = None
        if current:
            name = _deck_name(current)
            self.current = self.items.get(name) if name else None
        self._show_current()

        self.tree.itemDoubleClicked.connect(lambda *_: self._ok())
        self.search.textChanged.connect(self._filter)
        self.search.setFocus()

    def _show_current(self):
        it = self.current
        if it is None:
            return
        p = it.parent()
        while p is not None:
            p.setExpanded(True)
            p = p.parent()
        self.tree.setCurrentItem(it)
        self.tree.scrollToItem(it)

    def _filter(self, text):
        q = text.strip().lower()
        first = None
        for name, it in self.items.items():
            if not q:
                it.setHidden(False)
                continue
            hit = q in it.data(0, Qt.ItemDataRole.UserRole + 1).split("::")[-1]
            it.setHidden(not hit)
        if not q:
            self.tree.collapseAll()
            self._show_current()
            return
        # แสดงพ่อของ deck ที่ตรง และกางให้เห็น
        for name, it in self.items.items():
            if not it.isHidden():
                first = first or it
                p = it.parent()
                while p is not None:
                    p.setHidden(False)
                    p.setExpanded(True)
                    p = p.parent()
        if first is not None:
            self.tree.setCurrentItem(first)

    def _ok(self):
        it = self.tree.currentItem()
        if it is None or it.isHidden():
            return
        self.did = int(it.data(0, Qt.ItemDataRole.UserRole))
        self.accept()


class DeckRow(QWidget):
    def __init__(self, parent, entry=None):
        super().__init__(parent)
        self.did = entry["did"] if entry else None
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.btn = QPushButton()
        self.btn.clicked.connect(self.choose)
        self.base = QSpinBox()
        self.base.setRange(0, 9999)
        self.step = QSpinBox()
        self.step.setRange(0, 9999)
        clear = QPushButton("ลบ")
        clear.setToolTip("เอา deck นี้ออก")
        clear.clicked.connect(self.clear)
        lay.addWidget(self.btn, 1)
        lay.addWidget(QLabel("เริ่ม"))
        lay.addWidget(self.base)
        lay.addWidget(QLabel("เพิ่มครั้งละ"))
        lay.addWidget(self.step)
        lay.addWidget(clear)
        if entry:
            self.base.setValue(int(entry.get("base", 0)))
            self.step.setValue(int(entry.get("step", 0)))
        self._label()

    def _label(self):
        name = _deck_name(self.did) if self.did else None
        self.btn.setText(name or "เลือก deck…")
        for w in (self.base, self.step):
            w.setEnabled(bool(name))

    def choose(self):
        dlg = DeckPicker(self, self.did)
        if not dlg.exec() or not dlg.did:
            return
        self.did = dlg.did
        cur = _limit(self.did)
        self.base.setValue(cur)
        self.step.setValue(cur or 1)
        self._label()

    def clear(self):
        self.did = None
        self._label()

    def value(self):
        if not self.did or not _deck_name(self.did):
            return None
        return {"did": self.did, "base": self.base.value(), "step": self.step.value()}


class RoundsDialog(QDialog):
    UID = Qt.ItemDataRole.UserRole

    def __init__(self):
        super().__init__(mw)
        self.setWindowTitle("CardKit · ท่องเป็นรอบ")
        self.resize(760, 420)
        self.gs = []
        for k, g in enumerate(_cfg().get(KEY, [])):
            x = {"name": g.get("name", ""), "decks": list(g.get("decks", [])), "_uid": k}
            self.gs.append(x)
        self._next_uid = len(self.gs)
        root = QHBoxLayout(self)

        left = QVBoxLayout()
        left.addWidget(QLabel("ชุด (ลากเพื่อเรียง)"))
        self.list = QListWidget()
        self.list.setDragDropMode(QAbstractItemView.DragDropMode.InternalMove)
        self.list.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.list.model().rowsMoved.connect(lambda *_: QTimer.singleShot(0, self._sync_order))
        left.addWidget(self.list, 1)
        row = QHBoxLayout()
        add = QPushButton("＋ เพิ่มชุด")
        add.clicked.connect(self.add_group)
        rm = QPushButton("ลบ")
        rm.clicked.connect(self.remove_group)
        row.addWidget(add)
        row.addWidget(rm)
        left.addLayout(row)
        root.addLayout(left, 1)

        right = QVBoxLayout()
        self.box = QGroupBox("ตั้งค่าชุด")
        form = QFormLayout(self.box)
        self.name = QLineEdit()
        form.addRow("ชื่อชุด", self.name)
        self.rows = []
        grid = QVBoxLayout()
        for i in range(MAX_DECKS):
            r = DeckRow(self.box)
            self.rows.append(r)
            grid.addWidget(r)
        holder = QWidget()
        holder.setLayout(grid)
        form.addRow("deck (Maximum reviews/day)", holder)
        self.warn = QLabel()
        self.warn.setWordWrap(True)
        self.warn.setStyleSheet("color: #d97706;")
        form.addRow(self.warn)
        right.addWidget(self.box, 1)
        hint = QLabel("ตัวอย่างของคุณ: deck 1 เริ่ม 2 เพิ่มครั้งละ 2 · deck 2–3 เริ่ม 1 เพิ่มครั้งละ 1\n"
                      "→ รอบ 1 = 2/1/1, รอบ 2 = 4/2/2 … รอบ 5 = 10/5/5")
        hint.setStyleSheet("color: gray; font-size: 11px;")
        hint.setWordWrap(True)
        right.addWidget(hint)
        bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        right.addWidget(bb)
        root.addLayout(right, 3)

        self._cur = None          # uid ของชุดที่เปิดอยู่
        for g in self.gs:
            self._add_item(g)
        self.list.currentRowChanged.connect(self.select)
        if self.gs:
            self.list.setCurrentRow(0)
        else:
            self.add_group()

    def _add_item(self, g):
        it = QListWidgetItem(g.get("name") or "ชุดใหม่")
        it.setData(self.UID, g["_uid"])
        self.list.addItem(it)

    def _find(self, uid):
        return next((g for g in self.gs if g["_uid"] == uid), None)

    def _item(self, uid):
        for i in range(self.list.count()):
            if self.list.item(i).data(self.UID) == uid:
                return self.list.item(i)
        return None

    def _store(self):
        g = self._find(self._cur) if self._cur is not None else None
        if g is None:
            return
        decks = [v for v in (r.value() for r in self.rows) if v]
        g["name"] = self.name.text().strip() or "ชุดใหม่"
        g["decks"] = decks
        it = self._item(g["_uid"])
        if it is not None:
            it.setText(g["name"])

    def _sync_order(self):
        order = [self.list.item(i).data(self.UID) for i in range(self.list.count())]
        self.gs.sort(key=lambda g: order.index(g["_uid"]) if g["_uid"] in order else 999)

    def select(self, i):
        self._store()
        it = self.list.item(i) if i >= 0 else None
        self._cur = it.data(self.UID) if it is not None else None
        g = self._find(self._cur) if self._cur is not None else None
        self.box.setEnabled(g is not None)
        if g is None:
            return
        self.name.setText(g.get("name", ""))
        decks = g.get("decks", [])
        for k, r in enumerate(self.rows):
            entry = decks[k] if k < len(decks) else None
            r.did = entry["did"] if entry else None
            r.base.setValue(int(entry["base"]) if entry else 0)
            r.step.setValue(int(entry["step"]) if entry else 0)
            r._label()
        shared = _shared_presets(g) if decks else set()
        self.warn.setText("⚠ deck ที่ใช้ preset เดียวกันควรตั้ง \"เริ่ม\" และ \"เพิ่มครั้งละ\" เท่ากัน "
                          "(หรือแยก preset ใน Options)" if shared else "")

    def add_group(self):
        self._store()
        g = {"name": f"ชุด {len(self.gs) + 1}", "decks": [], "_uid": self._next_uid}
        self._next_uid += 1
        self.gs.append(g)
        self._add_item(g)
        self.list.setCurrentRow(self.list.count() - 1)

    def remove_group(self):
        i = self.list.currentRow()
        if i < 0:
            return
        uid = self.list.item(i).data(self.UID)
        self._cur = None
        self.gs = [g for g in self.gs if g["_uid"] != uid]
        self.list.takeItem(i)

    def accept(self):
        self._store()
        self._sync_order()
        save_groups([{"name": g["name"], "decks": g["decks"]} for g in self.gs if g["decks"]])
        align_presets()
        _rebuild_menu()
        if mw.state in ("overview",):
            mw.moveToState("overview")
        super().accept()


def open_settings():
    if mw.col is None:
        return
    RoundsDialog().exec()


# ---------------------------------------------------------------- UI hooks

_submenu = None


def _rebuild_menu():
    m = _submenu
    if m is None:
        return
    m.clear()
    gs = groups() if mw.col else []
    cur = group_for_current() if mw.col else None
    if cur is not None and _cfg().get(CUR_FIRST, True):
        gs = [g for g in gs if g["name"] == cur["name"]] + [g for g in gs if g["name"] != cur["name"]]
    for g in gs:
        mark = "  ← deck ที่เลือกอยู่" if cur and g["name"] == cur["name"] else ""
        wa = QWidgetAction(m)
        wa.setDefaultWidget(_GroupRow(m, g, mark))
        m.addAction(wa)
    if gs:
        if can_undo_all():
            m.addAction("↶ ยกเลิก (เริ่มใหม่ทุกชุด)", undo_reset_all)
        else:
            ra = m.addAction("↺ เริ่มใหม่ทุกชุด", reset_all)
            ra.setToolTip("ทุกชุดกลับเป็นค่าเริ่มต้นของตัวเอง เช่น 2/1/1 · ยกเลิกได้")
        m.addSeparator()
    mode = m.addAction("ใช้ช่อง Today only (กลับเป็นค่าเริ่มต้นเองตี 4 ทุกเครื่อง)")
    mode.setCheckable(True)
    mode.setChecked(today_mode())
    mode.triggered.connect(set_today_mode)
    bnew = m.addAction("จำนวนการ์ดที่เหลือ: บอก new ด้วย")
    bnew.setCheckable(True)
    bnew.setChecked(bool(_cfg().get(BROWSE_NEW, False)))

    def set_bnew(on):
        c = _cfg()
        c[BROWSE_NEW] = bool(on)
        mw.addonManager.writeConfig(ADDON, c)

    bnew.triggered.connect(set_bnew)
    first = m.addAction("ชุดของ deck ที่เลือกอยู่ขึ้นบนสุด")
    first.setCheckable(True)
    first.setChecked(bool(_cfg().get(CUR_FIRST, True)))

    def set_first(on):
        c = _cfg()
        c[CUR_FIRST] = bool(on)
        mw.addonManager.writeConfig(ADDON, c)

    first.triggered.connect(set_first)
    from . import bubble as _b
    bub = m.addAction("ปุ่มลอยวงกลม (แบบมือถือ)")
    bub.setCheckable(True)
    bub.setChecked(_b.enabled())
    bub.triggered.connect(_b.set_enabled)
    edge = m.addAction("      ↳ ชิดขอบ ซ่อนครึ่งหนึ่งเมื่อไม่ได้ใช้")
    edge.setCheckable(True)
    edge.setChecked(_b.edge_mode())
    edge.setEnabled(_b.enabled())
    edge.triggered.connect(_b.set_edge)
    rem = m.addAction("แสดงจำนวนการ์ดที่เหลือบนปุ่ม")
    rem.setCheckable(True)
    rem.setChecked(show_remaining())
    rem.triggered.connect(set_show_remaining)
    m.addSeparator()
    m.addAction("ตั้งค่าชุดท่อง…", open_settings)


def _outline_icon(kind, color):
    """ไอคอนเส้นโครงร่าง (สีตามตัวอักษรของเมนู: ธีมมืด = ขาว)"""
    from aqt.qt import QIcon, QPainter, QPen, QPixmap, QRectF, QPointF
    pm = QPixmap(48, 48)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    pen = QPen(color)
    pen.setWidthF(3.6)
    pen.setCapStyle(Qt.PenCapStyle.RoundCap)
    pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    p.setPen(pen)
    if kind == "plus":
        p.drawLine(QPointF(24, 9), QPointF(24, 39))
        p.drawLine(QPointF(9, 24), QPointF(39, 24))
    elif kind == "search":
        p.drawEllipse(QRectF(8, 8, 22, 22))
        p.drawLine(QPointF(27, 27), QPointF(39, 39))
    elif kind == "eye":
        from aqt.qt import QPainterPath
        path = QPainterPath()
        path.moveTo(4, 24)
        path.quadTo(24, 4, 44, 24)
        path.quadTo(24, 44, 4, 24)
        p.drawPath(path)
        p.drawEllipse(QRectF(17, 17, 14, 14))
    elif kind == "reset":
        r = QRectF(9, 9, 30, 30)
        p.drawArc(r, 100 * 16, 290 * 16)            # วงเกือบครบ เว้นช่องด้านบน
        p.drawLine(QPointF(20, 6), QPointF(27, 10))  # หัวลูกศร
        p.drawLine(QPointF(20, 14), QPointF(27, 10))
    p.end()
    return QIcon(pm)


def move_group(src, dst):
    """ย้ายชุดชื่อ src ไปไว้ตำแหน่งของชุดชื่อ dst"""
    gs = _cfg().get(KEY, [])
    names = [g.get("name") for g in gs]
    if src not in names or dst not in names or src == dst:
        return
    g = gs.pop(names.index(src))
    gs.insert([x.get("name") for x in gs].index(dst) + (1 if names.index(src) < names.index(dst) else 0), g)
    save_groups(gs)


_ROW_CSS = ("QPushButton{text-align:left;padding:5px 20px;border:none;%s}"
            "QPushButton:hover{background:palette(highlight);color:palette(highlighted-text);}")


class _GroupRow(QWidget):
    """แถวในเมนู: [➕ ชื่อชุด (ค่าปัจจุบัน)] ……… [↺]
    · ↺ มีคำอธิบายเฉพาะตอนชี้ · กดค้างแล้วลากขึ้นลงเพื่อเรียงชุด"""

    def __init__(self, menu, g, mark):
        super().__init__(menu)
        self.menu, self.g = menu, g
        self._press = None
        self._drag = False
        self._target = None
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 4, 0)
        lay.setSpacing(0)
        from aqt.qt import QPalette, QSize
        color = menu.palette().color(QPalette.ColorRole.Text)
        self.main = main = QPushButton(f" {g['name']}  ({summary(g)}){mark}")
        main.setIcon(_outline_icon("plus", color))
        main.setIconSize(QSize(13, 13))
        main.setFlat(True)
        main.setCursor(Qt.CursorShape.PointingHandCursor)
        main.setToolTip("คลิก = อีก 1 รอบ · กดค้างแล้วลาก = ย้ายตำแหน่ง")
        self.mark(False)
        main.clicked.connect(self._click)
        main.installEventFilter(self)
        look = QToolButton()
        look.setIcon(_outline_icon("search", color))
        look.setIconSize(QSize(14, 14))
        look.setAutoRaise(True)
        look.setCursor(Qt.CursorShape.PointingHandCursor)
        look.setToolTip(due_text(g))

        def do_look():
            from aqt.qt import QToolTip
            QToolTip.showText(look.mapToGlobal(look.rect().bottomLeft()), due_text(g), look)

        look.clicked.connect(do_look)
        rst = QToolButton()
        rst.setIcon(_outline_icon("reset", color))
        rst.setIconSize(QSize(14, 14))
        rst.setAutoRaise(True)
        rst.setCursor(Qt.CursorShape.PointingHandCursor)
        base = "/".join(str(d["base"]) for d in g["decks"])
        rst.setToolTip(f"เริ่มชุดใหม่: กลับเป็นค่าเริ่มต้น {base}")

        def do_reset():
            menu.close()
            QTimer.singleShot(0, lambda: askUser(f"คืนค่า \"{g['name']}\" กลับเป็นค่าเริ่มต้น {base}?",
                                                 parent=mw) and reset(g))

        rst.clicked.connect(do_reset)
        lay.addWidget(main, 1)
        lay.addWidget(look)
        lay.addWidget(rst)

    def mark(self, on):
        self.main.setStyleSheet(_ROW_CSS % ("border-top:2px solid palette(highlight);" if on else ""))

    def _click(self):
        if self._drag:
            return
        self.menu.close()
        bump(self.g)

    def _row_at(self, gpos):
        w = self.menu.childAt(self.menu.mapFromGlobal(gpos))
        while w is not None and not isinstance(w, _GroupRow):
            w = w.parentWidget()
        return w

    def eventFilter(self, obj, ev):
        from aqt.qt import QEvent
        t = ev.type()
        try:
            gpos = ev.globalPosition().toPoint()
        except AttributeError:
            gpos = getattr(ev, "globalPos", lambda: None)()
        if t == QEvent.Type.MouseButtonPress:
            self._press, self._drag = gpos, False
        elif t == QEvent.Type.MouseMove and self._press is not None:
            if not self._drag and (gpos - self._press).manhattanLength() > 8:
                self._drag = True
                self.main.setCursor(Qt.CursorShape.ClosedHandCursor)
            if self._drag:
                row = self._row_at(gpos)
                if row is not self._target:
                    if self._target is not None:
                        self._target.mark(False)
                    self._target = row if row is not self else None
                    if self._target is not None:
                        self._target.mark(True)
                return True
        elif t == QEvent.Type.MouseButtonRelease and self._drag:
            target, self._target, self._press = self._target, None, None
            self.main.setCursor(Qt.CursorShape.PointingHandCursor)
            if target is not None:
                target.mark(False)
                move_group(self.g["name"], target.g["name"])
                QTimer.singleShot(0, _rebuild_menu)      # เมนูยังเปิดอยู่ เห็นลำดับใหม่ทันที
            QTimer.singleShot(0, lambda: setattr(self, "_drag", False))
            return False                                  # ให้ปุ่มคลายตัว (_click เห็น _drag แล้วไม่ทำงาน)
        elif t == QEvent.Type.MouseButtonRelease:
            self._press = None
        return False


MENU_TITLE = "📚 Rounds"


def setup_menu():
    """เมนูแยกบนแถบเมนู วางถัดจาก 🃏 CardKit (ถ้าไม่มี วางถัดจาก Help)"""
    global _submenu
    from . import mqa
    bar = mw.form.menubar
    _submenu = QMenu(MENU_TITLE, mw)
    _submenu.aboutToShow.connect(_rebuild_menu)
    anchor = getattr(mqa, "_menu", None)
    anchor_act = anchor.menuAction() if anchor is not None else mw.form.menuHelp.menuAction()
    acts = bar.actions()
    k = acts.index(anchor_act) if anchor_act in acts else -1
    if 0 <= k < len(acts) - 1:
        bar.insertMenu(acts[k + 1], _submenu)
    else:
        bar.addMenu(_submenu)
    _rebuild_menu()
    mqa.HOVER_MENUS.append(_submenu)
    if mqa._hover is not None:
        mqa._hover.watch(_submenu)


def on_overview_bottom(link_handler, links):
    if mw.col is None:
        return link_handler
    g = group_for_current()
    if g is None:
        return link_handler
    label = button_label(g)
    if label is None:
        links.append(["", "cardkit_round_done", "✅ วันนี้ไม่มีการ์ดเหลือแล้ว"])
    else:
        links.append(["", "cardkit_round", label])

    def handler(url):
        if url == "cardkit_round":
            bump(g)
            return False
        if url == "cardkit_round_done":
            tooltip("ท่องครบทั้งชุดแล้ววันนี้ 🎉")
            return False
        return link_handler(url)

    return handler


gui_hooks.main_window_did_init.append(setup_menu)
gui_hooks.profile_did_open.append(align_presets)
gui_hooks.overview_will_render_bottom.append(on_overview_bottom)


# ---------------------------------------------------------------- due list on Congratulations

_EYE_SVG = ("<svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
            "stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round' style='vertical-align:-3px'>"
            "<path d='M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z'/><circle cx='12' cy='12' r='3'/></svg>")


def _set_due_html(g):
    """ตารางแบบหน้ารายการ deck: New · Learn · Due (ตามเพดานตอนนี้)
    + คอลัมน์ลูกตา: ชี้ = due ทั้งหมดที่เหลือ (วันนี้ + ค้าง ไม่สนเพดาน) · คลิกชื่อ = ไปท่อง deck นั้น"""
    import html as _h
    live = [d for d in g["decks"] if _deck_name(d["did"])]
    rows = due_counts(g)                      # [(ชื่อสั้น, new, learn, due ทั้งหมด)] ตามลำดับ live
    cn, cl, cr = _state_colors()
    tree = mw.col.sched.deck_due_tree()
    cur = mw.col.decks.current()
    cur_id, cur_name = cur["id"], cur["name"]

    def num(v, c):
        return f"<td class='ckn' style='color:{c if v else '#777'}'>{v}</td>"

    folded = set(_cfg().get(FOLD_KEY, []))

    idx = [0]

    def deck_row(d, name, learn, due, indent, parent_key):
        idx[0] += 1
        k = idx[0]
        badge = f"<span class='kn'>{k}</span>" if k <= 9 else ""
        node = mw.col.decks.find_deck_in_tree(tree, d["did"])
        n_new = node.new_count if node else 0
        n_learn = node.learn_count if node else 0
        n_due = node.review_count if node else 0
        left = int(learn + due)
        full = _deck_name(d["did"]) or ""
        here = d["did"] == cur_id or full.startswith(cur_name + "::")
        hide = " style='display:none'" if parent_key and parent_key in folded else ""
        pk = f" data-p='{_h.escape(parent_key, quote=True)}'" if parent_key else ""
        return (f"<tr class='{'here' if here else ''}'{pk}{hide}><td class='ckd' style='padding-left:{10 + indent}px'>"
                f"<a href='#' data-go='{d['did']}' onclick=\"pycmd('cardkit_go:{d['did']}');return false;\" "
                f"title='ไปท่อง deck นี้ (กด {k})'>{_h.escape(name)}</a>{badge}</td>"
                + num(n_new, cn) + num(n_learn, cl) + num(n_due, cr) +
                f"<td class='cke'><span class='eye'>{_EYE_SVG}<span class='tip'>เหลือ "
                f"<b style='color:{cr if left else '#888'}'>{left}</b></span></span></td></tr>")

    # จัดกลุ่มตาม deck แม่ (แบบหน้ารายการ deck: – / + กดพับได้)
    order, by_parent = [], {}
    for d, row in zip(live, rows):
        full = _deck_name(d["did"]) or ""
        parent = full.rsplit("::", 1)[0] if "::" in full else ""
        if parent not in by_parent:
            by_parent[parent] = []
            order.append(parent)
        by_parent[parent].append((d, row))
    trs = []
    for parent in order:
        if parent:
            pid = mw.col.decks.id_for_name(parent)
            pnode = mw.col.decks.find_deck_in_tree(tree, pid) if pid else None
            closed = parent in folded
            key = _h.escape(parent, quote=True)
            trs.append(
                f"<tr class='par' data-k='{key}'><td class='ckd'>"
                f"<span class='tg' onclick='ckFold(this)'>{'+' if closed else '–'}</span>"
                f"{_h.escape(parent.split('::')[-1])}</td>"
                + num(pnode.new_count if pnode else 0, cn) + num(pnode.learn_count if pnode else 0, cl)
                + num(pnode.review_count if pnode else 0, cr) + "<td></td></tr>")
        for d, (name, _new, learn, due) in by_parent[parent]:
            trs.append(deck_row(d, name, learn, due, 26 if parent else 0, parent))
    css = ("#ck-due{margin:26px auto 0;max-width:640px;text-align:left;font-size:15px}"
           "#ck-due .cap{color:#999;font-size:13px;margin-bottom:6px}"
           "#ck-due table{border-collapse:collapse;width:100%;table-layout:fixed}"
           "#ck-due th{color:#aaa;font-weight:600;text-align:right;padding:4px 10px;font-size:14px}"
           "#ck-due th:first-child{text-align:left}"
           "#ck-due td{padding:5px 10px}"
           "#ck-due .ckn{text-align:right}"
           "#ck-due .ckd a{color:inherit;text-decoration:none}"
           "#ck-due .ckd a:hover{text-decoration:underline}"
           "#ck-due tr.here td{background:rgba(128,128,128,.14)}"
           "#ck-due tr.here td:first-child{border-radius:8px 0 0 8px;font-weight:700}"
           "#ck-due tr.here td:last-child{border-radius:0 8px 8px 0}"
           "#ck-due .cke{color:#888}"
           "#ck-due .eye{cursor:default;opacity:.45;white-space:nowrap}"
           "#ck-due .eye:hover{opacity:1}"
           "#ck-due .eye .tip{display:none;margin-left:6px;font-size:13px}"
           "#ck-due .eye:hover .tip{display:inline}"
           "#ck-due tr.par td{color:#ccc}"
           "#ck-due .tg{display:inline-block;width:18px;cursor:pointer;color:#aaa;user-select:none}"
           "#ck-due .tg:hover{color:#fff}"
           "#ck-due .kn{display:inline-block;margin-left:8px;padding:0 5px;border-radius:4px;font-size:11px;"
           "line-height:16px;color:#999;border:1px solid #555;vertical-align:1px}")
    head = ("<tr><th>ชุด " + _h.escape(g["name"]) + "</th><th>New</th><th>Learn</th><th>Due</th>"
            f"<th title='ชี้ลูกตา = due ที่เหลือทั้งหมด (วันนี้ + ค้าง)'>{_EYE_SVG}</th></tr>")
    return (f"<style>{css}</style><div class='cap'>คลิกชื่อ deck หรือกดเลข 1–9 เพื่อท่องต่อ · "
            f"ชี้ลูกตา = due ที่เหลือทั้งหมด (วันนี้ + ค้าง)</div>"
            "<table><colgroup><col><col style='width:72px'><col style='width:72px'><col style='width:72px'>"
            f"<col style='width:96px'></colgroup>{head}{''.join(trs)}</table>")


_INJECT_JS = """
window.ckFold = function (el) {   // – / + พับ deck ย่อย (จำไว้ใน config)
  var tr = el.closest('tr'), k = tr.getAttribute('data-k'), c = el.textContent === '–';
  el.textContent = c ? '+' : '–';
  document.querySelectorAll('#ck-due tr[data-p]').forEach(function (r) {
    if (r.getAttribute('data-p') === k) r.style.display = c ? 'none' : '';
  });
  pycmd('cardkit_fold:' + (c ? 1 : 0) + ':' + k);
};
if (!window.ckKeys) {               // หน้าท่องเสร็จ: กด 1–9 = ไป deck ย่อยแถวนั้น
  window.ckKeys = true;
  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    var t = e.target && e.target.tagName;
    if (t === 'INPUT' || t === 'TEXTAREA') return;
    if (!/^[1-9]$/.test(e.key)) return;
    var links = document.querySelectorAll('#ck-due a[data-go]');
    var a = links[parseInt(e.key, 10) - 1];
    if (!a) return;
    e.preventDefault();
    pycmd('cardkit_go:' + a.getAttribute('data-go'));
  });
}
(function (html) {
  function put() {
    if (document.getElementById('ck-due')) return true;
    var host = document.querySelector('main') || document.body;
    if (!host || !(host.innerText || '').trim()) return false;
    var d = document.createElement('div');
    d.id = 'ck-due';
    d.innerHTML = html;
    host.appendChild(d);
    return true;
  }
  if (!put()) {
    var t = setInterval(function () { if (put()) clearInterval(t); }, 150);
    setTimeout(function () { clearInterval(t); }, 6000);
  }
  if (!window.ckDueObs) {
    window.ckDueObs = new MutationObserver(function () { put(); });
    window.ckDueObs.observe(document.body, { childList: true, subtree: true });
  }
})(%s);
"""


def on_overview_refresh(overview):
    """หน้า Congratulations (deck ท่องครบ) ของ deck ที่อยู่ในชุด → แทรกรายการ due ของชุดนั้น"""
    import json
    try:
        if mw.col is None or not mw.col.sched._is_finished():
            return
        g = group_for_current()
        if g is None:
            return
        overview.web.eval(_INJECT_JS % json.dumps(_set_due_html(g)))
    except Exception as e:
        print("[rounds due list]", e)


def on_js_message(handled, message, context):
    if isinstance(message, str) and message.startswith("cardkit_fold:"):
        _, on, key = message.split(":", 2)
        c = _cfg()
        f = set(c.get(FOLD_KEY, []))
        (f.add if on == "1" else f.discard)(key)
        c[FOLD_KEY] = sorted(f)
        mw.addonManager.writeConfig(ADDON, c)
        return (True, None)
    if not (isinstance(message, str) and message.startswith("cardkit_go:")):
        return handled
    try:
        did = int(message.split(":", 1)[1])
        mw.col.decks.select(did)
        node = mw.col.decks.find_deck_in_tree(mw.col.sched.deck_due_tree(), did)
        ready = node is not None and (node.review_count + node.learn_count + node.new_count) > 0
        if ready:
            mw.col.startTimebox()
            mw.moveToState("review")
        else:
            mw.moveToState("overview")
            tooltip("deck นี้ติดเพดานรอบแล้ว · กด ➕ อีก 1 รอบ เพื่อท่องต่อ")
    except Exception as e:
        tooltip(f"เปิด deck ไม่ได้: {e}")
    return (True, None)


gui_hooks.overview_did_refresh.append(on_overview_refresh)
gui_hooks.webview_did_receive_js_message.append(on_js_message)
