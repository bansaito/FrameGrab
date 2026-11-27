"""
เข้าท่องอัตโนมัติ (ระบบใหม่ · ตัวจับเวลาอยู่ฝั่ง Anki ไม่ขึ้นกับหน้าเว็บ)

  อยู่หน้า Decks แล้ว "ไม่แตะอะไรเลย"
     รอ (auto_start_delay วิ)  →  เส้นเขียววิ่งรอบแถว deck (auto_start_seconds วิ)  →  เข้าท่อง
  • ขยับเมาส์ / คลิก / เลื่อน / กดปุ่ม = เริ่มนับใหม่ (ไม่ใช่ยกเลิกถาวร)
  • Esc = หยุดนับ จนกว่าจะออกไปหน้าอื่นแล้วกลับมา
  • ไม่นับตอน sync/โหลด หรือมีหน้าต่างอื่นอยู่ด้านหน้า (Add, Browse …)
  • deck ที่เลือก: การ์ดเยอะสุด (เท่ากัน = อันบนสุด) · เฉพาะที่โชว์อยู่ (ไม่ซ่อน / ไม่อยู่ใต้ deck ที่พับ)
  • เส้นเขียวเป็นแค่ภาพ ถ้าวาดไม่ขึ้นก็ยังเข้าท่องได้                              สวิตช์ auto_start_countdown

  ท่องจบ deck → เข้า deck ถัดไปเอง (ในชุด Rounds เดียวกัน · ไม่อยู่ในชุด = deck ไหนก็ได้)   สวิตช์ auto_next_deck
"""

import time

from aqt import gui_hooks, mw
import os

from aqt.qt import QApplication, QDialog, QEvent, QMainWindow, QObject, Qt, QTimer
from aqt.utils import tooltip

from .mqa import ADDON, cfg, on

TICK_MS = 200


# ---------------------------------------------------------------- เลือก deck

def _tree_info():
    """{did: (ลำดับในต้นไม้, จำนวนการ์ดที่ท่องได้ตอนนี้, did แม่)}"""
    out = {}

    def walk(node, parent):
        for ch in node.children:
            out[ch.deck_id] = (len(out), ch.new_count + ch.learn_count + ch.review_count, parent)
            walk(ch, ch.deck_id)
    walk(mw.col.sched.deck_due_tree(), None)
    return out


def _shown_on_page(info):
    """deck ที่โชว์อยู่หน้า Decks จริง (ไม่ถูกซ่อน · deck แม่ไม่ได้พับ)"""
    vis = None
    try:
        from . import deckfilter
        r = deckfilter._visible_ids()
        vis = r[0] if r else None
        hidden = set(deckfilter._manual())
    except Exception:
        hidden = set()
    out = set()
    for did, (_o, _n, parent) in info.items():
        if did in hidden or (vis is not None and did not in vis):
            continue
        p, ok = parent, True
        while p is not None:
            d = mw.col.decks.get(p, default=False)
            if not d or d.get("collapsed"):
                ok = False
                break
            p = info.get(p, (0, 0, None))[2]
        if ok:
            out.add(did)
    return out


def _candidates(groups=None, visible_only=True):
    """[(did, จำนวน, ลำดับ)] เรียง: การ์ดเยอะสุด → อยู่บนสุด"""
    info = _tree_info()
    dids = list(info) if groups is None else [d["did"] for g in groups for d in g["decks"]]
    shown = _shown_on_page(info) if visible_only else None
    seen, out = set(), []
    for did in dids:
        if did in seen or did not in info:
            continue
        seen.add(did)
        order, n, _p = info[did]
        if n > 0 and (shown is None or did in shown):
            out.append((did, n, order))
    out.sort(key=lambda x: (-x[1], x[2]))
    return out


def _start(did):
    try:
        mw.col.decks.select(did)
        mw.col.startTimebox()
        mw.moveToState("review")
    except Exception as e:
        print("[cardkit autostart]", e)


def _secs(key, default, lo):
    try:
        return max(lo, min(60, int(cfg().get(key, default))))
    except (TypeError, ValueError):
        return default


def _other_window_in_front():
    """มีหน้าต่างจริงอื่น (Add, Browse, Edit, ตั้งค่า …) อยู่ข้างหน้า · หน้าต่างลอยเล็ก ๆ ของ add-on ไม่นับ"""
    if QApplication.activeModalWidget() is not None or QApplication.activePopupWidget() is not None:
        return "popup/modal"
    aw = QApplication.activeWindow()
    if aw is None or aw is mw:
        return ""
    if isinstance(aw, (QDialog, QMainWindow)) and aw.isVisible():
        return type(aw).__name__
    return ""


def _app_inactive():
    try:
        return QApplication.instance().applicationState() != Qt.ApplicationState.ApplicationActive
    except Exception:
        return False


# บันทึกสั้น ๆ ว่าทำไมยังไม่นับ (ไว้ส่งให้ดูถ้าไม่ทำงาน) · ไฟล์ autostart_log.txt ในโฟลเดอร์ add-on
_LOG = os.path.join(os.path.dirname(__file__), "autostart_log.txt")
_last_reason = {"r": None}


def _log(reason):
    if reason == _last_reason["r"]:
        return
    _last_reason["r"] = reason
    try:
        lines = []
        if os.path.exists(_LOG):
            with open(_LOG, encoding="utf-8") as f:
                lines = f.read().splitlines()[-60:]
        lines.append(time.strftime("%H:%M:%S ") + reason)
        with open(_LOG, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    except Exception:
        pass


# ---------------------------------------------------------------- พัก / สลับเปิดปิด

def pause(minutes):
    c = cfg()
    c["auto_pause_until"] = time.time() + minutes * 60
    mw.addonManager.writeConfig(ADDON, c)
    if _auto is not None:
        _auto.reset()
    tooltip(f"☕ พัก {minutes} นาที · ไม่เข้าท่องเอง", period=1800)


def resume():
    c = cfg()
    c.pop("auto_pause_until", None)
    mw.addonManager.writeConfig(ADDON, c)
    if _auto is not None:
        _auto.reset()
    tooltip("เลิกพักแล้ว", period=1200)


def paused_left():
    """เหลือเวลาพักกี่วินาที (0 = ไม่ได้พัก)"""
    try:
        return max(0, int(float(cfg().get("auto_pause_until", 0)) - time.time()))
    except (TypeError, ValueError):
        return 0


def toggle():
    from .mqa import KEYS
    c = cfg()
    key = KEYS["auto_start"]
    new = not on("auto_start")
    c[key] = new
    if new:
        c.pop("auto_pause_until", None)
    mw.addonManager.writeConfig(ADDON, c)
    if _auto is not None:
        _auto.reset()
    tooltip("เข้าท่องเอง: " + ("เปิด" if new else "ปิด"), period=1500)


def _busy():
    try:
        return bool(mw.progress.busy())
    except Exception:
        return False


# ---------------------------------------------------------------- เส้นเขียว (แค่ภาพ)

_DRAW_JS = r"""
(function(did, ms){
  var old = document.getElementById('ck-run'); if (old) old.remove();
  var tr = document.getElementById(String(did)); if (!tr) return;
  var r = tr.getBoundingClientRect();
  if (r.top < 0 || r.bottom > window.innerHeight) { tr.scrollIntoView({block: 'center'}); r = tr.getBoundingClientRect(); }
  if (!document.getElementById('ck-run-css')) {
    var st = document.createElement('style'); st.id = 'ck-run-css';
    st.textContent = "@property --ckp{syntax:'<angle>';inherits:false;initial-value:0deg}"
      + "@keyframes ckfill{to{--ckp:360deg}}"
      + "#ck-run{position:absolute;pointer-events:none;border-radius:10px;padding:2px;z-index:9999;"
      + "background:conic-gradient(from -90deg,#22c55e var(--ckp),rgba(34,197,94,.12) 0);"
      + "-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);"
      + "-webkit-mask-composite:xor;mask-composite:exclude}";
    document.head.appendChild(st);
  }
  var o = document.createElement('div'); o.id = 'ck-run';
  o.style.left = (r.left + window.scrollX - 4) + 'px'; o.style.top = (r.top + window.scrollY - 3) + 'px';
  o.style.width = (r.width + 4) + 'px'; o.style.height = (r.height + 2) + 'px';
  o.style.animation = 'ckfill ' + (ms / 1000) + 's linear forwards';
  document.body.appendChild(o);
})(%d, %d);
"""
_CLEAR_JS = "(function(){var o=document.getElementById('ck-run'); if(o) o.remove();})();"


def _eval(js):
    try:
        if mw.state == "deckBrowser":
            mw.deckBrowser.web.eval(js)
    except Exception:
        pass


# ---------------------------------------------------------------- ตัวจับเวลา

class _Auto(QObject):
    def __init__(self):
        super().__init__(mw)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.last_input = time.monotonic()
        self.phase = "idle"          # idle (รอ) / run (เส้นวิ่ง)
        self.run_did = None
        self.run_end = 0.0
        self.stopped = False         # กด Esc แล้ว (จนกว่าจะออกจากหน้า Decks)
        self.anchor = None           # ตำแหน่งเมาส์ล่าสุด (กันเมาส์สั่นนิดหน่อย)

    def enter_decks(self):
        _log("เข้าหน้า Decks")
        self.stopped = False
        self.reset()
        if not self.timer.isActive():
            self.timer.start(TICK_MS)

    def leave_decks(self):
        self.timer.stop()
        self._end_run()
        self.stopped = False

    def reset(self):
        self.last_input = time.monotonic()
        self._end_run()

    def _end_run(self):
        if self.phase == "run":
            _eval(_CLEAR_JS)
        self.phase = "idle"
        self.run_did = None

    def _tick(self):
        if mw.state != "deckBrowser":
            self.leave_decks()
            return
        if not on("auto_start"):
            _log("ปิดสวิตช์อยู่")
            self._end_run()
            return
        if self.stopped:
            _log("กด Esc หยุดไว้")
            self._end_run()
            return
        if paused_left() > 0:
            _log("☕ พักอยู่")
            self.reset()
            return
        why = "กำลัง sync/โหลด" if _busy() else ("ใช้แอปอื่นอยู่" if _app_inactive() else _other_window_in_front())
        if why:
            _log("ยังไม่นับ: " + why)
            self.reset()                     # sync / หน้าต่างอื่นอยู่ข้างหน้า → ยังไม่นับ
            return
        now = time.monotonic()
        if self.phase == "idle":
            if now - self.last_input < _secs("auto_start_delay", 3, 0):
                _log("รอไม่แตะอะไร")
                return
            try:
                c = _candidates()
            except Exception as e:
                print("[cardkit autostart]", e)
                c = []
            if not c:
                _log("ไม่มี deck ที่มีการ์ดโชว์อยู่")
                self.last_input = now        # ไม่มี deck ให้เข้า → รอรอบหน้า
                return
            _log(f"เริ่มเส้นเขียว deck {c[0][0]} ({c[0][1]} ใบ)")
            run = _secs("auto_start_seconds", 3, 1)
            self.phase, self.run_did, self.run_end = "run", c[0][0], now + run
            _eval(_DRAW_JS % (self.run_did, run * 1000))
        elif now >= self.run_end:
            did = self.run_did
            self._end_run()
            self.timer.stop()
            _log(f"เข้าท่อง deck {did}")
            _start(did)

    # ผู้ใช้แตะอะไร = เริ่มนับใหม่ · Esc = หยุดจนกว่าจะออกจากหน้า Decks
    def eventFilter(self, obj, ev):
        if not self.timer.isActive():
            return False
        t = ev.type()
        if t == QEvent.Type.KeyPress:
            _log("แตะ: กดปุ่ม")
            if ev.key() == Qt.Key.Key_Escape and not self.stopped:
                self.stopped = True
                try:
                    tooltip("หยุดเข้าท่องอัตโนมัติ (จนกว่าจะออกจากหน้านี้)", period=1500)
                except Exception:
                    pass
            self.reset()
        elif t in (QEvent.Type.MouseButtonPress, QEvent.Type.Wheel):
            _log("แตะ: คลิก/เลื่อน")
            self.reset()
        elif t == QEvent.Type.MouseMove:
            try:
                p = ev.globalPosition().toPoint()
            except Exception:
                return False
            if self.anchor is None or (abs(p.x() - self.anchor.x()) + abs(p.y() - self.anchor.y())) >= 30:
                self.anchor = p
                _log("แตะ: ขยับเมาส์")
                self.reset()
        return False


_auto = None


def _install():
    global _auto
    if _auto is None:
        _auto = _Auto()
        QApplication.instance().installEventFilter(_auto)
        _log("— เริ่มระบบ —")


def _on_state(new, old):
    if _auto is None:
        return
    if new == "deckBrowser":
        _auto.enter_decks()
    elif old == "deckBrowser":
        _auto.leave_decks()
    if new == "overview" and old == "review":
        QTimer.singleShot(0, _next_deck)


def _on_profile_open():
    _install()
    # Anki วาดหน้า Decks ก่อนสัญญาณนี้ → เริ่มนับเองถ้าอยู่หน้า Decks แล้ว
    QTimer.singleShot(300, lambda: _auto.enter_decks() if mw.state == "deckBrowser" else None)


def _on_render(deck_browser):
    # หน้าถูกวาดใหม่ระหว่างเส้นวิ่ง (sync / refresh) → วาดเส้นใหม่ตามเวลาที่เหลือ
    if _auto is not None and _auto.phase == "run" and _auto.run_did:
        left = max(200, int((_auto.run_end - time.monotonic()) * 1000))
        did = _auto.run_did
        QTimer.singleShot(50, lambda: _eval(_DRAW_JS % (did, left)))


# ---------------------------------------------------------------- ท่องจบ → deck ถัดไป

def _next_deck():
    if not on("auto_next") or mw.state != "overview" or mw.col is None:
        return
    try:
        if not mw.col.sched._is_finished():
            return                                   # ออกมาเองกลางทาง ไม่ใช่ท่องจบ
        from . import rounds as R
        cur = mw.col.decks.get_current_id()
        g = next((g for g in R.groups() if any(d["did"] == cur for d in g["decks"])), None)
        nxt = next((c for c in _candidates([g] if g else None, visible_only=g is None) if c[0] != cur), None)
        if nxt is None:
            return                                   # หมดแล้ว → หน้า Congratulations ตามปกติ
        tooltip(f"ต่อ: {mw.col.decks.name(nxt[0]).split('::')[-1]}", period=1500)
        _start(nxt[0])
    except Exception as e:
        print("[cardkit next deck]", e)


gui_hooks.main_window_did_init.append(_install)
gui_hooks.profile_did_open.append(_on_profile_open)
gui_hooks.state_did_change.append(_on_state)
gui_hooks.deck_browser_did_render.append(_on_render)
