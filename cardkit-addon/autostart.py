"""
เข้าท่องอัตโนมัติ (เฉพาะ deck ในชุด Rounds ที่โชว์อยู่หน้า Decks · deck ที่ซ่อนไม่นับ)
  • เปิด Anki ครั้งแรก: แถว deck ที่การ์ดเยอะสุด (เท่ากัน = อันบนกว่า) มีเส้นขอบวิ่งครบรอบใน 3 วิ
    ไม่กดอะไร → เข้าท่อง deck นั้น · กดปุ่ม/คลิก/เลื่อน/ขยับเมาส์ = ยกเลิก        สวิตช์ auto_start_countdown
    (auto_start_every_time = ทุกครั้งที่อยู่หน้า Decks · auto_start_seconds = กี่วิ)
  • ท่องจบ deck → เข้า deck ถัดไปในชุดเดียวกันเอง (การ์ดเยอะสุดก่อน) จนหมดชุด      สวิตช์ auto_next_deck
"""

import json

from aqt import gui_hooks, mw
from aqt.qt import QTimer
from aqt.utils import tooltip

from .mqa import on

_st = {"pending": False}          # รอนับถอยหลังตอนเปิดแอป (จนกว่าจะเข้า/ยกเลิก)


def _manual_hidden():
    try:
        from . import deckfilter
        return set(deckfilter._manual())
    except Exception:
        return set()


def _tree_info():
    """{did: (ลำดับในต้นไม้, จำนวนการ์ดที่ท่องได้ตอนนี้)}"""
    out = {}

    def walk(node):
        for ch in node.children:
            out[ch.deck_id] = (len(out), ch.new_count + ch.learn_count + ch.review_count)
            walk(ch)
    walk(mw.col.sched.deck_due_tree())
    return out


def _candidates(groups=None):
    """[(did, จำนวน, ลำดับ)] deck ที่ยังมีการ์ด · ไม่รวมที่ซ่อนเอง
    groups=None = ทุก deck · ส่งชุดมา = เฉพาะ deck ในชุดนั้น"""
    info = _tree_info()
    hidden = _manual_hidden()
    if groups is None:
        dids = list(info)
    else:
        dids = [d["did"] for g in groups for d in g["decks"]]
    seen, out = set(), []
    for did in dids:
        if did in seen or did in hidden or did not in info:
            continue
        seen.add(did)
        order, n = info[did]
        if n > 0:
            out.append((did, n, order))
    out.sort(key=lambda x: (-x[1], x[2]))          # การ์ดเยอะสุดก่อน · เท่ากัน = อยู่บนกว่า
    return out


def _start(did):
    try:
        mw.col.decks.select(did)
        mw.col.startTimebox()
        mw.moveToState("review")
    except Exception as e:
        print("[cardkit autostart]", e)


# ---------------------------------------------------------------- นับถอยหลังตอนเปิดแอป

_JS = r"""
(function(cands, ms, delay){
  if (window.__ckAuto) return; window.__ckAuto = 1;
  function pick(){
    var best = null;
    cands.forEach(function(c){                         // c = [did, n] เรียงมาแล้ว (เยอะสุด/บนสุดก่อน)
      if (best) return;
      var tr = document.getElementById(String(c[0]));
      if (tr && tr.classList.contains('deck') && tr.offsetParent !== null && tr.style.display !== 'none') best = [c[0], tr];
    });
    return best;
  }
  function go(tries){
    var b = pick();
    if (!b) { if (tries < 8) setTimeout(function(){ go(tries + 1); }, 200); else pycmd('ck_auto:none'); return; }
    var tr = b[1], r = tr.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight) tr.scrollIntoView({block: 'center'});
    r = tr.getBoundingClientRect();
    if (!document.getElementById('ck-auto-css')) {
      var st = document.createElement('style'); st.id = 'ck-auto-css';
      st.textContent = "@property --ckp{syntax:'<angle>';inherits:false;initial-value:0deg}"
        + "@keyframes ckfill{to{--ckp:360deg}}"
        + ".ck-run{position:absolute;pointer-events:none;border-radius:10px;padding:2px;z-index:9999;"
        + "background:conic-gradient(from -90deg,#22c55e var(--ckp),rgba(34,197,94,.12) 0);"
        + "-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);"
        + "-webkit-mask-composite:xor;mask-composite:exclude;animation:ckfill " + (ms / 1000) + "s linear forwards}";
      document.head.appendChild(st);
    }
    var o = document.createElement('div'); o.className = 'ck-run';
    o.style.left = (r.left + window.scrollX - 4) + 'px'; o.style.top = (r.top + window.scrollY - 3) + 'px';
    o.style.width = (r.width + 4) + 'px'; o.style.height = (r.height + 2) + 'px';
    document.body.appendChild(o);
    var done = false, x0 = null, y0 = null;
    var evs = ['keydown', 'mousedown', 'wheel', 'touchstart', 'mousemove'];
    function stop(send){
      if (done) return; done = true; o.remove();
      evs.forEach(function(e){ window.removeEventListener(e, cancel, true); });
      pycmd(send);
    }
    function cancel(e){
      if (e.type === 'mousemove') {                    // ขยับเมาส์นิดหน่อยไม่นับ
        if (x0 === null) { x0 = e.clientX; y0 = e.clientY; return; }
        if (Math.abs(e.clientX - x0) + Math.abs(e.clientY - y0) < 30) return;
      }
      stop('ck_auto:cancel');
    }
    evs.forEach(function(e){ window.addEventListener(e, cancel, true); });
    setTimeout(function(){ stop('ck_auto:go:' + b[0]); }, ms);
  }
  // รอเฉย ๆ ก่อนเริ่ม (delay) · ระหว่างรอ ถ้าผู้ใช้กด/คลิก/เลื่อน/ขยับเมาส์ = ยกเลิกเลย
  if (!delay) { go(0); return; }
  var pre = ['keydown', 'mousedown', 'wheel', 'touchstart', 'mousemove'], px = null, py = null, gone = false;
  function preCancel(e){
    if (e.type === 'mousemove') {
      if (px === null) { px = e.clientX; py = e.clientY; return; }
      if (Math.abs(e.clientX - px) + Math.abs(e.clientY - py) < 30) return;
    }
    gone = true; clearTimeout(tm);
    pre.forEach(function(ev){ window.removeEventListener(ev, preCancel, true); });
    pycmd('ck_auto:cancel');
  }
  pre.forEach(function(ev){ window.addEventListener(ev, preCancel, true); });
  var tm = setTimeout(function(){
    pre.forEach(function(ev){ window.removeEventListener(ev, preCancel, true); });
    if (!gone) go(0);
  }, delay);
})(%s, %d, %d);
"""


def _secs():
    from .mqa import cfg
    try:
        return max(1, min(60, int(cfg().get("auto_start_seconds", 3) or 3)))
    except (TypeError, ValueError):
        return 3


def _delay():
    """รอก่อนเริ่มนับ (วิ) · 0 = เริ่มนับทันที"""
    from .mqa import cfg
    try:
        return max(0, min(60, int(cfg().get("auto_start_delay", 3))))
    except (TypeError, ValueError):
        return 3


def _busy():
    try:
        return bool(mw.progress.busy())
    except Exception:
        return False


def _go_when_ready(did, tries=0):
    """นับครบแล้ว แต่ถ้ากำลัง sync อยู่ → รอจนเสร็จแล้วค่อยเข้า (ยังอยู่หน้า Decks เท่านั้น)"""
    if mw.state != "deckBrowser" or _st.get("cancelled"):
        return
    if _busy() and tries < 60:
        QTimer.singleShot(500, lambda: _go_when_ready(did, tries + 1))
        return
    _start(did)


def _on_profile_open():
    _st["pending"] = bool(on("auto_start"))
    _st["cancelled"] = False
    # Anki วาดหน้า Decks ก่อนส่งสัญญาณ "เปิดโปรไฟล์แล้ว" → ครั้งแรกเรายังไม่พร้อม · สั่งเริ่มเองอีกที
    if _st["pending"]:
        QTimer.singleShot(400, _kick)


def _kick():
    try:
        if mw.state == "deckBrowser" and mw.deckBrowser is not None:
            _on_render(mw.deckBrowser)
    except Exception as e:
        print("[cardkit autostart]", e)


def _on_render(deck_browser):
    """อยู่หน้า Decks เมื่อไหร่ = นับถอยหลัง (ยกเลิกแล้วไม่นับซ้ำจนกว่าจะออกไปหน้าอื่นแล้วกลับมา)"""
    if not on("auto_start") or _st.get("cancelled"):
        return
    if _busy():                                     # กำลัง sync / โหลดอยู่ → รอเสร็จก่อนค่อยนับ (กันชนกัน)
        QTimer.singleShot(700, _kick)
        return
    try:
        cands = [[did, n] for did, n, _o in _candidates()]   # ทุก deck ที่มีการ์ดและโชว์อยู่
        if not cands:
            return
        deck_browser.web.eval(_JS % (json.dumps(cands), _secs() * 1000, _delay() * 1000))
    except Exception as e:
        print("[cardkit autostart]", e)


def _on_msg(handled, message, context):
    if not (isinstance(message, str) and message.startswith("ck_auto:")):
        return handled
    _st["pending"] = False
    parts = message.split(":")
    if parts[1] == "cancel":
        _st["cancelled"] = True          # ยกเลิกแล้ว ไม่นับอีกจนกว่าจะออกไปหน้าอื่นแล้วกลับมา
    if len(parts) == 3 and parts[1] == "go" and mw.state == "deckBrowser":
        try:
            did = int(parts[2])
        except ValueError:
            return (True, None)
        QTimer.singleShot(0, lambda: _go_when_ready(did))
    return (True, None)


def _on_state(new, old):
    if new != "deckBrowser" and old == "deckBrowser":
        _st["pending"] = False                       # ไปหน้าอื่นเองแล้ว → ไม่ต้องนับ
        _st["cancelled"] = False                     # กลับมาหน้า Decks ครั้งหน้า = นับใหม่ได้ (โหมดทุกครั้ง)
    if new == "overview" and old == "review":
        QTimer.singleShot(0, _next_deck)


# ---------------------------------------------------------------- ท่องจบ → deck ถัดไปในชุด

def _next_deck():
    if not on("auto_next") or mw.state != "overview" or mw.col is None:
        return
    try:
        if not mw.col.sched._is_finished():
            return                                   # ออกมาเองกลางทาง ไม่ใช่ท่องจบ
        from . import rounds as R
        cur = mw.col.decks.get_current_id()
        g = next((g for g in R.groups() if any(d["did"] == cur for d in g["decks"])), None)
        # อยู่ในชุด Rounds = ไปต่อในชุดเดียวกัน · ไม่อยู่ในชุด = deck ไหนก็ได้ที่ยังมีการ์ด (ไม่รวมที่ซ่อน)
        nxt = next((c for c in _candidates([g] if g else None) if c[0] != cur), None)
        if nxt is None:
            return                                   # หมดทั้งชุดแล้ว → หน้า Congratulations ตามปกติ
        tooltip(f"ต่อ: {mw.col.decks.name(nxt[0]).split('::')[-1]}", period=1500)
        _start(nxt[0])
    except Exception as e:
        print("[cardkit next deck]", e)


gui_hooks.profile_did_open.append(_on_profile_open)
gui_hooks.deck_browser_did_render.append(_on_render)
gui_hooks.webview_did_receive_js_message.append(_on_msg)
gui_hooks.state_did_change.append(_on_state)
