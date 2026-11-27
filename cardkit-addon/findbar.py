"""
หน้าทบทวน: ⌘F (Mac) / Ctrl+F (Windows) = ค้นหา + แทนที่
  • ช่องค้นหาลอยมุมขวาบน · บอก "N จาก M" · ▲▼ / Enter / Shift+Enter = ก่อนหน้า/ถัดไป · Esc = ปิด
  • ⋮ = เปิดช่องแทนที่ → "แทนที่" (ตัวที่เลือกอยู่) / "แทนที่ทั้งหมด" → แก้ในโน้ตจริง (Ctrl+Z ย้อนได้)
  • ช่องว่างก็นับ (เว้นวรรคธรรมดากับ &nbsp; ถือเป็นตัวเดียวกัน) · ไม่สนตัวพิมพ์เล็ก/ใหญ่
"""

import html as _html
import json
import re

from aqt import gui_hooks, mw
from aqt.qt import QTimer
from aqt.utils import tooltip

from .mqa import on

_JS = r"""
<style>
#ck-find{position:fixed;top:12px;right:16px;z-index:99999;background:#fff;color:#1f2328;border-radius:14px;
  box-shadow:0 6px 24px rgba(0,0,0,.28);padding:10px 12px;font:14px/1.4 system-ui,-apple-system,"Segoe UI",sans-serif;
  text-align:left;display:none;min-width:330px}
.nightMode #ck-find,.night_mode #ck-find{background:#2b2d31;color:#e8e8e8}
#ck-find .row{display:flex;align-items:center;gap:6px}
#ck-find .box{flex:1;display:flex;align-items:center;border:2px solid #2563eb;border-radius:10px;padding:4px 8px}
#ck-find .box.rep{border:1px solid rgba(128,128,128,.6)}
#ck-find input{flex:1;border:none;outline:none;background:transparent;color:inherit;font:inherit;min-width:80px}
#ck-find .cnt{opacity:.7;white-space:nowrap;margin-left:6px;font-size:13px}
#ck-find .b{cursor:pointer;padding:3px 7px;border-radius:8px;user-select:none;opacity:.8}
#ck-find .b:hover{background:rgba(128,128,128,.18);opacity:1}
#ck-find .rep-row{margin-top:8px;display:none}
#ck-find.rep-on .rep-row{display:flex}
#ck-find .act{color:#2563eb;font-weight:600;cursor:pointer;padding:4px 8px;border-radius:8px;white-space:nowrap}
#ck-find .act:hover{background:rgba(37,99,235,.12)}
mark.ck-hit{background:#fde68a;color:#111;border-radius:2px;padding:0}
mark.ck-hit.cur{background:#f59e0b;color:#111}
</style>
<script>
(function(){
  if (window.ckFind) return;
  var bar, qi, ri, cnt, hits = [], cur = -1;
  function norm(s){ return s.replace(/ /g, ' ').toLowerCase(); }
  function build(){
    bar = document.createElement('div'); bar.id = 'ck-find';
    bar.innerHTML =
      '<div class="row"><div class="box"><input id="ck-fq" placeholder="ค้นหาในการ์ด"><span class="cnt" id="ck-fc"></span></div>' +
      '<span class="b" id="ck-fp" title="ก่อนหน้า (Shift+Enter)">&#9650;</span>' +
      '<span class="b" id="ck-fn" title="ถัดไป (Enter)">&#9660;</span>' +
      '<span class="b" id="ck-fm" title="แทนที่">&#8942;</span>' +
      '<span class="b" id="ck-fx" title="ปิด (Esc)">&#10005;</span></div>' +
      '<div class="row rep-row"><div class="box rep"><input id="ck-fr" placeholder="แทนที่ด้วย"></div>' +
      '<span class="act" id="ck-f1">แทนที่</span><span class="act" id="ck-fa">แทนที่ทั้งหมด</span></div>';
    document.body.appendChild(bar);
    qi = bar.querySelector('#ck-fq'); ri = bar.querySelector('#ck-fr'); cnt = bar.querySelector('#ck-fc');
    qi.addEventListener('input', function(){ run(true); });
    [qi, ri].forEach(function(inp){
      inp.addEventListener('keydown', function(e){
        e.stopPropagation();
        if (e.key === 'Escape') { e.preventDefault(); close(); }
        else if (e.key === 'Enter' && inp === qi) { e.preventDefault(); step(e.shiftKey ? -1 : 1); }
        else if (e.key === 'Enter' && inp === ri) { e.preventDefault(); replace(false); }
      });
      inp.addEventListener('keyup', function(e){ e.stopPropagation(); });
      inp.addEventListener('keypress', function(e){ e.stopPropagation(); });
    });
    bar.querySelector('#ck-fp').onclick = function(){ step(-1); };
    bar.querySelector('#ck-fn').onclick = function(){ step(1); };
    bar.querySelector('#ck-fx').onclick = close;
    bar.querySelector('#ck-fm').onclick = function(){ bar.classList.toggle('rep-on'); if (bar.classList.contains('rep-on')) ri.focus(); };
    bar.querySelector('#ck-f1').onclick = function(){ replace(false); };
    bar.querySelector('#ck-fa').onclick = function(){ replace(true); };
  }
  function clear(){
    document.querySelectorAll('mark.ck-hit').forEach(function(m){
      var p = m.parentNode; p.replaceChild(document.createTextNode(m.textContent), m); p.normalize();
    });
    hits = []; cur = -1;
  }
  function run(keepPos){
    var q = qi.value, root = document.getElementById('qa') || document.body;
    var oldCur = cur;
    clear();
    if (!q) { cnt.textContent = ''; return; }
    var nq = norm(q), w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function(n){ return (n.parentNode && n.parentNode.closest && n.parentNode.closest('#ck-find, script, style'))
        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; } });
    var nodes = [], n; while ((n = w.nextNode())) nodes.push(n);
    nodes.forEach(function(node){
      var t = norm(node.data), idx = t.indexOf(nq), pos = [];
      while (idx >= 0) { pos.push(idx); idx = t.indexOf(nq, idx + nq.length); }
      if (!pos.length) return;
      var frag = document.createDocumentFragment(), last = 0, d = node.data;
      pos.forEach(function(p){
        if (p > last) frag.appendChild(document.createTextNode(d.slice(last, p)));
        var m = document.createElement('mark'); m.className = 'ck-hit'; m.textContent = d.slice(p, p + nq.length);
        frag.appendChild(m); hits.push(m); last = p + nq.length;
      });
      if (last < d.length) frag.appendChild(document.createTextNode(d.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
    cur = hits.length ? Math.min(Math.max(keepPos ? oldCur : 0, 0), hits.length - 1) : -1;
    show();
  }
  function show(){
    hits.forEach(function(h, i){ h.classList.toggle('cur', i === cur); });
    cnt.textContent = hits.length ? (cur + 1) + ' จาก ' + hits.length : (qi.value ? 'ไม่พบ' : '');
    if (cur >= 0) hits[cur].scrollIntoView({block: 'center'});
  }
  function step(d){ if (!hits.length) return; cur = (cur + d + hits.length) % hits.length; show(); }
  function ctx(m, before){
    // ข้อความรอบ ๆ ตัวที่เลือก (ใช้หาว่าเป็นคำไหนในโน้ต)
    var root = document.getElementById('qa') || document.body, w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), s = '', n, hit = false, out = '';
    while ((n = w.nextNode())) {
      if (n.parentNode && n.parentNode.closest && n.parentNode.closest('#ck-find')) continue;
      if (m.contains(n)) { hit = true; if (before) return s.slice(-30); continue; }
      if (hit) { out += n.data; if (out.length >= 30) break; } else s += n.data;
    }
    return before ? s.slice(-30) : out.slice(0, 30);
  }
  function replace(all){
    var q = qi.value; if (!q) return;
    var data = {q: q, r: ri.value, all: all};
    if (!all) { if (cur < 0) return; data.before = ctx(hits[cur], true); data.after = ctx(hits[cur], false); }
    pycmd('ckfind:' + JSON.stringify(data));
  }
  function open(){
    if (!bar) build();
    bar.style.display = 'block'; qi.focus(); qi.select();
    if (qi.value) run(true);
  }
  function close(){ clear(); if (bar) bar.style.display = 'none'; }
  window.ckFind = {
    open: open, close: close,
    rerun: function(){ if (bar && bar.style.display === 'block' && qi.value) run(true); },
  };
})();
</script>
"""


def _is_reviewer(context):
    return any(c.__name__ == "Reviewer" for c in type(context).__mro__)


def _on_set_content(web_content, context):
    if on("find_replace") and _is_reviewer(context):
        web_content.head += _JS


def _open():
    if mw.state == "review" and on("find_replace"):
        mw.reviewer.web.setFocus()
        mw.reviewer.web.eval("window.ckFind && ckFind.open();")


def _on_shortcuts(state, shortcuts):
    if state == "review" and on("find_replace"):
        shortcuts.append(("Ctrl+F", _open))      # Mac = ⌘F (Qt แปลง Ctrl เป็น Command ให้เอง)


# ---------------------------------------------------------------- แทนที่ในโน้ตจริง

_SPLIT = re.compile(r"(<[^>]*>)")


def _segments(field_html):
    """[(index ในรายการ, ข้อความจริง (unescape แล้ว))] เฉพาะส่วนที่ไม่ใช่แท็ก"""
    parts = _SPLIT.split(field_html or "")
    return parts, [(i, _html.unescape(p)) for i, p in enumerate(parts) if i % 2 == 0 and p]


def _norm(s):
    return s.replace("\xa0", " ").lower()


def _escape(s):
    return _html.escape(s, quote=False).replace("\xa0", "&nbsp;")


def _find_all(text, q):
    t, nq, out, i = _norm(text), _norm(q), [], 0
    if not nq:
        return out
    i = t.find(nq)
    while i >= 0:
        out.append(i)
        i = t.find(nq, i + len(nq))
    return out


def replace_all(fields, q, r):
    """คืน (fields ใหม่, จำนวนที่แทนที่)"""
    total, out = 0, []
    for f in fields:
        parts, segs = _segments(f)
        for i, txt in segs:
            pos = _find_all(txt, q)
            if not pos:
                continue
            new, last = [], 0
            for p in pos:
                new.append(txt[last:p])
                new.append(r)
                last = p + len(q)
            new.append(txt[last:])
            parts[i] = _escape("".join(new))
            total += len(pos)
        out.append("".join(parts))
    return out, total


def _common_suffix(a, b):
    n = 0
    while n < min(len(a), len(b)) and _norm(a[-1 - n]) == _norm(b[-1 - n]):
        n += 1
    return n


def _common_prefix(a, b):
    n = 0
    while n < min(len(a), len(b)) and _norm(a[n]) == _norm(b[n]):
        n += 1
    return n


def replace_one(fields, q, r, before, after):
    """แทนที่ตัวที่ข้อความรอบ ๆ ตรงกับในหน้าการ์ดมากที่สุด · คืน (fields ใหม่, 0/1)"""
    best = None
    for fi, f in enumerate(fields):
        parts, segs = _segments(f)
        plain = "".join(t for _i, t in segs)
        off = 0
        for si, txt in segs:
            for p in _find_all(txt, q):
                g = off + p
                score = _common_suffix(plain[:g], before) + _common_prefix(plain[g + len(q):], after)
                if best is None or score > best[0]:
                    best = (score, fi, si, p)
            off += len(txt)
    if best is None:
        return fields, 0
    _s, fi, si, p = best
    parts, segs = _segments(fields[fi])
    txt = dict(segs)[si]
    parts[si] = _escape(txt[:p] + r + txt[p + len(q):])
    out = list(fields)
    out[fi] = "".join(parts)
    return out, 1


def _on_msg(handled, message, context):
    if not (isinstance(message, str) and message.startswith("ckfind:")):
        return handled
    try:
        d = json.loads(message[7:])
        card = mw.reviewer.card
        if card is None:
            return (True, None)
        note = card.note()
        if d.get("all"):
            new, n = replace_all(list(note.fields), d["q"], d.get("r", ""))
        else:
            new, n = replace_one(list(note.fields), d["q"], d.get("r", ""),
                                 d.get("before", ""), d.get("after", ""))
        if not n:
            tooltip("ไม่พบคำนี้ในเนื้อโน้ต (อาจเป็นข้อความที่ add-on/เทมเพลตเติมให้)", period=2500)
            return (True, None)
        for i, v in enumerate(new):
            note.fields[i] = v
        from aqt.operations.note import update_note
        update_note(parent=mw, note=note).success(
            lambda _o: QTimer.singleShot(700, lambda: mw.reviewer.web.eval(
                "window.ckFind && ckFind.open();"))).run_in_background()
        tooltip(f"แทนที่แล้ว {n} ที่ (Ctrl+Z ย้อนได้)", period=1800)
    except Exception as e:
        print("[cardkit find]", e)
    return (True, None)


def _on_show(_card):
    try:
        mw.reviewer.web.eval("window.ckFind && ckFind.rerun();")
    except Exception:
        pass


gui_hooks.webview_will_set_content.append(_on_set_content)
gui_hooks.state_shortcuts_will_change.append(_on_shortcuts)
gui_hooks.webview_did_receive_js_message.append(_on_msg)
gui_hooks.reviewer_did_show_question.append(_on_show)
gui_hooks.reviewer_did_show_answer.append(_on_show)
