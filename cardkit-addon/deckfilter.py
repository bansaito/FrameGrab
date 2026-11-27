"""
หน้า Decks ไม่รก + เปิด Anki แล้วเข้าท่องเลย
  • ซ่อน deck ที่ไม่มีการ์ดวันนี้ (New/Learn/Due = 0 หมด)            สวิตช์ decks_hide_empty
  • แสดงเฉพาะ deck ในชุด Rounds                                       สวิตช์ decks_rounds_only
    (ทั้งสองอย่างเก็บ deck แม่ไว้ให้ต้นไม้ไม่ขาด · ท้ายตารางมีปุ่ม "แสดงทั้งหมด")
  • เปิด Anki → เข้าท่องชุดหลักทันที (ถ้าชุดหลักมีการ์ด)              สวิตช์ start_study_main
"""

import json

from aqt import gui_hooks, mw
from aqt.qt import QTimer

from .mqa import on

_show_all = {"on": False}            # กด "แสดงทั้งหมด" ไว้ (จนปิด Anki)


def _walk(node, out, parent=None):
    for ch in node.children:
        out[ch.deck_id] = (parent, ch.new_count + ch.learn_count + ch.review_count)
        _walk(ch, out, ch.deck_id)


def _visible_ids():
    """deck ที่จะแสดง · None = ไม่กรอง"""
    hide_empty = on("decks_hide_empty")
    rounds_only = on("decks_rounds_only")
    if not (hide_empty or rounds_only) or _show_all["on"] or mw.col is None:
        return None
    info = {}
    _walk(mw.col.sched.deck_due_tree(), info)
    allowed = None
    if rounds_only:
        from . import rounds as R
        base = {d["did"] for g in R.groups() for d in g["decks"]}
        if base:
            allowed = set()
            for did in info:                       # deck ในชุด + deck ลูกของมัน
                p = did
                while p is not None:
                    if p in base:
                        allowed.add(did)
                        break
                    p = info.get(p, (None, 0))[0]
    show = set()
    for did, (_parent, n) in info.items():
        if allowed is not None and did not in allowed:
            continue
        if hide_empty and n <= 0:
            continue
        show.add(did)
    for did in list(show):                         # เก็บ deck แม่ไว้ ต้นไม้จะได้ไม่ขาด
        p = info[did][0]
        while p is not None and p not in show:
            show.add(p)
            p = info.get(p, (None, 0))[0]
    return show, len(info) - len(show)


_JS = r"""
(function(show, hidden, all){
  function run(){
    var rows = document.querySelectorAll('tr.deck');
    if (!rows.length) return false;
    rows.forEach(function(tr){
      var id = parseInt(tr.id, 10);
      tr.style.display = (all || !show || show.indexOf(id) >= 0) ? '' : 'none';
    });
    var old = document.getElementById('ck-showall'); if (old) old.remove();
    if ((show === null || !hidden) && !all) return true;   // ไม่มีอะไรถูกซ่อน → ไม่ต้องมีปุ่ม
    var tbl = rows[0].closest('table');
    var row = document.createElement('tr'); row.id = 'ck-showall';
    var td = document.createElement('td'); td.colSpan = 6;
    td.style.cssText = 'text-align:center;padding:10px 0 2px;opacity:.55;font-size:.9em;cursor:pointer';
    td.textContent = all ? 'ซ่อน deck ที่ไม่ต้องท่อง' : ('แสดงทั้งหมด' + (hidden ? ' (ซ่อนอยู่ ' + hidden + ')' : ''));
    td.onclick = function(){ pycmd('ck_dball:' + (all ? 0 : 1)); };
    row.appendChild(td); (tbl.tBodies[0] || tbl).appendChild(row);
    return true;
  }
  [0, 150, 500, 1200].forEach(function(t){ setTimeout(run, t); });   // add-on แดชบอร์ดอาจวาดช้า
})(%s, %d, %s);
"""


def _on_render(deck_browser):
    try:
        if not (on("decks_hide_empty") or on("decks_rounds_only")):
            return
        r = _visible_ids()
        show, hidden = (sorted(r[0]), r[1]) if r else (None, 0)
        deck_browser.web.eval(_JS % (json.dumps(show), hidden, "true" if _show_all["on"] else "false"))
    except Exception as e:
        print("[cardkit deckfilter]", e)


def _on_msg(handled, message, context):
    if not (isinstance(message, str) and message.startswith("ck_dball:")):
        return handled
    _show_all["on"] = message.endswith("1")
    try:
        mw.deckBrowser.refresh()
    except Exception:
        pass
    return (True, None)


# ---------------------------------------------------------------- เปิด Anki → ท่องชุดหลัก

_started = {"done": False}


def _start_study(tries=0):
    if _started["done"]:
        return
    try:
        if mw.progress.busy() and tries < 30:           # กำลัง sync/โหลดอยู่ → รอก่อน
            QTimer.singleShot(1000, lambda: _start_study(tries + 1))
            return
    except Exception:
        pass
    _started["done"] = True
    try:
        if not on("start_study_main") or mw.col is None or mw.state != "deckBrowser":
            return
        from . import bubble
        from . import rounds as R
        g = bubble.primary_group()
        if g is None or bubble.capped_left(g) <= 0:
            return                                   # ชุดหลักไม่มีการ์ด → อยู่หน้า Decks ตามปกติ
        R.study_set(g)
    except Exception as e:
        print("[cardkit start study]", e)


def _on_profile_open():
    _started["done"] = False
    QTimer.singleShot(1200, _start_study)            # รอหน้า Decks / sync ตอนเปิดเสร็จก่อน


gui_hooks.deck_browser_did_render.append(_on_render)
gui_hooks.webview_did_receive_js_message.append(_on_msg)
gui_hooks.profile_did_open.append(_on_profile_open)
