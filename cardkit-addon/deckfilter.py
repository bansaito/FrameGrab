"""
หน้า Decks ไม่รก + เปิด Anki แล้วเข้าท่องเลย
  • ซ่อน deck ที่ไม่มีการ์ดวันนี้ (New/Learn/Due = 0 หมด)            สวิตช์ decks_hide_empty
  • แสดงเฉพาะ deck ในชุด Rounds                                       สวิตช์ decks_rounds_only
    (ทั้งสองอย่างเก็บ deck แม่ไว้ให้ต้นไม้ไม่ขาด · ท้ายตารางมีปุ่ม "แสดงทั้งหมด")
"""

import json

from aqt import gui_hooks, mw

from .mqa import on

_show_all = {"on": False}            # กด "แสดงทั้งหมด" ไว้ (จนปิด Anki)
CFG_MANUAL = "decks_manual_hidden"   # deck ที่กด − ซ่อนเอง (จำถาวร)


def _manual():
    from .mqa import cfg
    return [int(x) for x in (cfg().get(CFG_MANUAL) or [])]


def _save_manual(ids):
    from .mqa import ADDON, cfg
    c = cfg()
    c[CFG_MANUAL] = sorted({int(x) for x in ids})
    mw.addonManager.writeConfig(ADDON, c)


_collapsed = set()      # deck ที่พับอยู่ในหน้า Decks (ลูกไม่ถูกวาด)


def _walk(node, out, parent=None):
    for ch in node.children:
        out[ch.deck_id] = (parent, ch.new_count + ch.learn_count + ch.review_count)
        if getattr(ch, "collapsed", False):
            _collapsed.add(ch.deck_id)
        _walk(ch, out, ch.deck_id)


def _rendered(did, info):
    """แถวนี้ถูกวาดในหน้า Decks ไหม (deck แม่ทุกชั้นต้องกางอยู่)"""
    p = info.get(did, (None, 0))[0]
    while p is not None:
        if p in _collapsed:
            return False
        p = info.get(p, (None, 0))[0]
    return True


def _visible_ids():
    """deck ที่จะแสดง · None = ไม่กรอง"""
    hide_empty = on("decks_hide_empty")
    rounds_only = on("decks_rounds_only")
    if _show_all["on"] or mw.col is None:
        return None
    if not (hide_empty or rounds_only):
        if not _manual():
            return None
        info = {}
        _walk(mw.col.sched.deck_due_tree(), info)
        show = set(info) - set(_manual())
        return show, len(info) - len(show)
    info = {}
    _collapsed.clear()
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
    hidden_manual = set(_manual())
    show -= hidden_manual
    # แสดง deck แม่ของ deck ที่ต้องท่อง (ถึงจะเป็น 0) ให้รู้ว่าอยู่ใต้อะไร · ยกเว้นที่กด − ซ่อนเอง
    # เฉพาะ deck ลูกที่ถูกวาดจริง (deck แม่กางอยู่) — deck แม่ที่พับอยู่และเป็น 0 ไม่ต้องโชว์
    for did in [d for d in show if _rendered(d, info)]:
        p = info[did][0]
        while p is not None:
            if p not in hidden_manual:
                show.add(p)
            p = info.get(p, (None, 0))[0]
    drawn = [d for d in info if _rendered(d, info)]
    return show, len([d for d in drawn if d not in show])


_JS = r"""
(function(show, hidden, all, manual, auto, handles){
  if (!document.getElementById('ck-dbh-css')) {
    var st = document.createElement('style'); st.id = 'ck-dbh-css';
    // ชิดซ้ายสุดของตาราง เป็นแถวเดียวกันทุก deck (ไม่ขยับตามการย่อหน้า)
    st.textContent = '.ck-hcell{position:relative;padding-left:24px!important}'
      + '.ck-h{position:absolute;left:2px;top:0;bottom:0;width:20px;display:flex;align-items:center;justify-content:center;opacity:0;'
      + 'cursor:pointer;user-select:none;transition:opacity .12s;font-weight:bold}'
      + 'tr.deck:hover .ck-h{opacity:.6}.ck-h:hover{opacity:1!important}';
    document.head.appendChild(st);
  }
  function run(){
    var rows = Array.prototype.slice.call(document.querySelectorAll('tr.deck'));
    if (!rows.length) return false;
    var ids = rows.map(function(tr){ return parseInt(tr.id, 10); });
    var vis = ids.map(function(id){ return all || !show || show.indexOf(id) >= 0; });
    rows.forEach(function(tr, i){ tr.style.display = vis[i] ? '' : 'none'; });
    // กลุ่มที่ต้องท่อง (แถวแรก–แถวสุดท้ายที่ auto โชว์) → ใช้ตัดสินว่ากด − แล้วซ่อนขึ้นบนหรือลงล่าง
    var autoIdx = ids.map(function(id, i){ return (!auto || auto.indexOf(id) >= 0) ? i : -1; })
                     .filter(function(i){ return i >= 0; });
    var first = autoIdx.length ? autoIdx[0] : 0;
    var isMan = ids.map(function(id){ return manual.indexOf(id) >= 0; });
    rows.forEach(function(tr, i){
      var old = tr.querySelector('.ck-h'); if (old) old.remove();
      if (!handles || !vis[i]) return;
      var cell = tr.cells[0]; if (!cell) return;
      cell.classList.add('ck-hcell');
      // แถวติดกับส่วนที่ซ่อนเอง → + (เปิดคืน) · ไม่งั้น − (ซ่อน)
      // ข้ามแถวที่ถูกซ่อนอัตโนมัติ (deck ว่าง) ไปหาส่วนที่ซ่อนเอง
      function hiddenRun(step){
        var out = [], k = i + step, anyMan = false;
        while (k >= 0 && k < rows.length && !vis[k]) { if (isMan[k]) { anyMan = true; out.push(ids[k]); } k += step; }
        return anyMan ? out : null;
      }
      var below = hiddenRun(1), above = hiddenRun(-1);
      if (all) {                                         // โหมดแสดงทั้งหมด: แถวที่เคยซ่อนเอง = + (เลิกซ่อน)
        below = null; above = null;
        if (isMan[i]) { var run = [], k = i; while (k > 0 && isMan[k - 1]) k--; for (; k < rows.length && isMan[k]; k++) run.push(ids[k]); below = run; }
      }
      var h = document.createElement('span'); h.className = 'ck-h';
      if (below || above) {
        h.textContent = '+'; h.title = 'แสดง deck ที่ซ่อนไว้';
        h.onclick = function(e){
          e.preventDefault(); e.stopPropagation();
          pycmd('ck_dbunhide:' + (below || []).concat(above || []).join(','));
        };
      } else {
        h.textContent = '−';
        var up = i < first;
        h.title = up ? 'ซ่อนแถวนี้และด้านบนทั้งหมด' : 'ซ่อนแถวนี้และด้านล่างทั้งหมด';
        h.onclick = function(e){
          e.preventDefault(); e.stopPropagation();
          var out = up ? ids.slice(0, i + 1) : ids.slice(i);
          pycmd('ck_dbhide:' + out.join(','));
        };
      }
      cell.appendChild(h);
    });
    var old2 = document.getElementById('ck-showall'); if (old2) old2.remove();
    if ((show === null || !hidden) && !all) return true;
    var tbl = rows[0].closest('table');
    var row = document.createElement('tr'); row.id = 'ck-showall';
    var td = document.createElement('td'); td.colSpan = 6;
    td.style.cssText = 'text-align:center;padding:10px 0 2px;opacity:.45;font-size:.9em;cursor:pointer';
    td.textContent = all ? 'ซ่อนกลับ' : ('แสดงทั้งหมด' + (hidden ? ' (ซ่อนอยู่ ' + hidden + ')' : ''));
    td.onclick = function(){ pycmd('ck_dball:' + (all ? 0 : 1)); };
    row.appendChild(td); (tbl.tBodies[0] || tbl).appendChild(row);
    return true;
  }
  [0, 150, 500, 1200].forEach(function(t){ setTimeout(run, t); });   // add-on แดชบอร์ดอาจวาดช้า
})(%s, %d, %s, %s, %s, %s);
"""


def _on_render(deck_browser):
    try:
        if not (on("decks_hide_empty") or on("decks_rounds_only") or on("decks_hover_hide")):
            return
        r = _visible_ids()
        show, hidden = (sorted(r[0]), r[1]) if r else (None, 0)
        man = _manual() if on("decks_hover_hide") else []
        info = {}
        _walk(mw.col.sched.deck_due_tree(), info)
        auto = sorted(d for d, (_p, n) in info.items() if n > 0) or None   # deck ที่มีการ์ด → หาว่าแถวอยู่บน/ล่างกลุ่มท่อง
        deck_browser.web.eval(_JS % (json.dumps(show), hidden, "true" if _show_all["on"] else "false",
                                     json.dumps(man), json.dumps(auto),
                                     "true" if on("decks_hover_hide") else "false"))
    except Exception as e:
        print("[cardkit deckfilter]", e)


def _on_msg(handled, message, context):
    if not isinstance(message, str):
        return handled
    if message.startswith(("ck_dbhide:", "ck_dbunhide:")):
        cmd, _, arg = message.partition(":")
        ids = {int(x) for x in arg.split(",") if x.strip().lstrip("-").isdigit()}
        cur = set(_manual())
        _save_manual(cur | ids if cmd == "ck_dbhide" else cur - ids)
        try:
            mw.deckBrowser.refresh()
        except Exception:
            pass
        return (True, None)
    if not message.startswith("ck_dball:"):
        return handled
    _show_all["on"] = message.endswith("1")
    try:
        mw.deckBrowser.refresh()
    except Exception:
        pass
    return (True, None)


gui_hooks.deck_browser_did_render.append(_on_render)
gui_hooks.webview_did_receive_js_message.append(_on_msg)
