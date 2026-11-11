"""
หน้าต่าง Choose Deck (ย้าย deck / เลือก deck ตอน Add / Study Deck …) แสดงเป็นต้นไม้ พับ/กาง deck ย่อยได้
  • คลิก ▸ / ▾ หน้าชื่อ = กาง/พับ   · ปุ่ม → กาง  ← พับ (หรือขึ้นไป deck แม่)
  • พิมพ์ใน Filter = แสดงชื่อเต็มแบบเดิม (ค้นหาง่าย)
  • จำว่ากางอันไหนไว้ · deck ที่เลือกอยู่จะกางทางไปให้อัตโนมัติ
"""

from aqt import mw
from aqt import studydeck as _sd
from aqt.qt import QAbstractItemView, QEvent, QFontMetrics, QObject, Qt

from .mqa import ADDON, cfg, on

CFG_OPEN = "deck_picker_open"
_IND = "      "


def _key(n):
    return [p.lower() for p in n.split("::")]


def _opened():
    return set(cfg().get(CFG_OPEN) or [])


def _save_opened(s):
    try:
        c = mw.addonManager.getConfig(ADDON) or {}
        c[CFG_OPEN] = sorted(s)
        mw.addonManager.writeConfig(ADDON, c)
    except Exception:
        pass


def _ancestors(n):
    p = n.split("::")
    return ["::".join(p[:i]) for i in range(1, len(p))]


def _tree_on(self):
    return on("deck_tree") and not (getattr(self, "filt", "") or "").strip()


_orig_redraw = _sd.StudyDeck.redraw
_orig_filter = _sd.StudyDeck.eventFilter


def _redraw(self, filt, focus=None):
    if not on("deck_tree") or (filt or "").strip():
        self._ck_tree = None
        return _orig_redraw(self, filt, focus)
    self.filt = filt
    self.focus = focus
    allnames = sorted(self.origNames, key=_key)
    present = set(allnames)
    opened = _opened()
    if not getattr(self, "_ck_path_done", False) and focus:
        opened |= set(_ancestors(focus))           # เปิดหน้าต่าง: กางทางไป deck ที่เลือกอยู่
        self._ck_path_done = True
        self._ck_session_open = set(_ancestors(focus))
    opened |= getattr(self, "_ck_session_open", set())
    opened -= getattr(self, "_ck_session_closed", set())
    parents = set()
    for n in allnames:
        parents.update(_ancestors(n))
    fm = QFontMetrics(self.form.list.font())
    hs = "\u200a"                                   # hair space: ช่องว่างละเอียด ให้ deck ที่ไม่มีลูกตรงกับชื่อที่มี ▸
    pad = hs * max(1, round(fm.horizontalAdvance("▾ ") / max(1, fm.horizontalAdvance(hs))))
    rows, names = [], []
    for n in allnames:
        if any(a in present and a not in opened for a in _ancestors(n)):
            continue
        depth = n.count("::")
        mark = ("▾ " if n in opened else "▸ ") if n in parents else pad
        rows.append(_IND * depth + mark + n.split("::")[-1])
        names.append(n)
    self.names = names
    self._ck_tree = {"opened": opened, "parents": parents}
    lw = self.form.list
    lw.clear()
    lw.addItems(rows)
    for i, n in enumerate(names):
        lw.item(i).setToolTip(n)
    idx = names.index(focus) if focus in names else 0
    if names:
        lw.setCurrentRow(idx)
        lw.scrollToItem(lw.item(idx), QAbstractItemView.ScrollHint.PositionAtCenter)
    _hook_clicks(self)


def _toggle(self, name, want=None):
    t = getattr(self, "_ck_tree", None)
    if not t or name not in t["parents"]:
        return False
    is_open = name in t["opened"]
    new = (not is_open) if want is None else want
    if new == is_open:
        return False
    s = _opened()
    so = getattr(self, "_ck_session_open", set())
    sc = getattr(self, "_ck_session_closed", set())
    if new:
        s.add(name); so.add(name); sc.discard(name)
    else:
        s.discard(name); so.discard(name); sc.add(name)
    self._ck_session_open, self._ck_session_closed = so, sc
    _save_opened(s)
    _redraw(self, "", name)
    return True


def _event_filter(self, obj, evt):
    try:
        if evt.type() == QEvent.Type.KeyPress and _tree_on(self) and getattr(self, "_ck_tree", None):
            k = evt.key()
            row = self.form.list.currentRow()
            if row >= 0 and k in (Qt.Key.Key_Right, Qt.Key.Key_Left):
                name = self.names[row]
                if k == Qt.Key.Key_Right:
                    _toggle(self, name, True)
                elif not _toggle(self, name, False) and "::" in name:
                    parent = name.rsplit("::", 1)[0]          # พับไม่ได้ → ไป deck แม่
                    if parent in self.names:
                        self.form.list.setCurrentRow(self.names.index(parent))
                return True
    except Exception:
        pass
    return _orig_filter(self, obj, evt)


class _Clicks(QObject):
    """คลิกที่ ▸/▾ = กาง/พับ (คลิกที่ชื่อ = เลือกตามปกติ)"""

    def __init__(self, dlg):
        super().__init__(dlg)
        self.dlg = dlg

    def eventFilter(self, obj, ev):
        if ev.type() not in (QEvent.Type.MouseButtonPress, QEvent.Type.MouseButtonDblClick):
            return False
        d = self.dlg
        try:
            if not _tree_on(d) or not getattr(d, "_ck_tree", None):
                return False
            lw = d.form.list
            pos = ev.position().toPoint()
            it = lw.itemAt(pos)
            if it is None:
                return False
            row = lw.row(it)
            name = d.names[row]
            if name not in d._ck_tree["parents"]:
                return False
            fm = QFontMetrics(lw.font())
            depth = name.count("::")
            x0 = lw.visualItemRect(it).left() + fm.horizontalAdvance(_IND * depth)
            if x0 - 6 <= pos.x() <= x0 + fm.horizontalAdvance("▾ ") + 8:
                _toggle(d, name)
                return True
        except Exception:
            pass
        return False


def _hook_clicks(self):
    if getattr(self, "_ck_clicks", None) is None:
        self._ck_clicks = _Clicks(self)
        self.form.list.viewport().installEventFilter(self._ck_clicks)


_sd.StudyDeck.redraw = _redraw
_sd.StudyDeck.eventFilter = _event_filter


# ---------------------------------------------------------------- หน้า Import (เมนู Deck เป็นหน้าเว็บ)
# ทำต้นไม้ในรายการ dropdown ของหน้า Import: คลิก ▸/▾ = กาง/พับ · คลิกชื่อ = เลือก

_IMPORT_JS = r"""
(function(opened){
  if (window.__ckDeckTree) return;
  window.__ckDeckTree = 1;
  var open = {}; opened.forEach(function(n){ open[n] = 1; });
  function send(){ var f = window.pycmd || window.bridgeCommand; if (f) f('ck_dtopen:' + JSON.stringify(Object.keys(open))); }
  function anc(n){ var p = n.split('::'), out = []; for (var i = 1; i < p.length; i++) out.push(p.slice(0, i).join('::')); return out; }
  function apply(list){
    var btns = list.__ckBtns, parents = list.__ckParents;
    btns.forEach(function(b){
      var n = b.__ckName, hide = anc(n).some(function(a){ return list.__ckHas[a] && !open[a]; });
      b.style.display = hide ? 'none' : '';
      var ar = b.querySelector('.ck-ar');
      if (ar) ar.textContent = parents[n] ? (open[n] ? '▾' : '▸') : '';
    });
  }
  function treeify(list){
    var btns = Array.prototype.slice.call(list.querySelectorAll('button[role=option]'));
    if (btns.length < 3 || list.__ckDone) return;
    var names = btns.map(function(b){ return (b.textContent || '').trim(); });
    if (!names.some(function(n){ return n.indexOf('::') > 0; })) return;
    list.__ckDone = 1;
    var has = {}, parents = {};
    names.forEach(function(n){ has[n] = 1; anc(n).forEach(function(a){ parents[a] = 1; }); });
    btns.forEach(function(b, i){
      var n = names[i], d = n.split('::').length - 1;
      b.__ckName = n; b.title = n;
      b.textContent = '';
      var pad = document.createElement('span'); pad.style.cssText = 'display:inline-block;width:' + (d * 1.4) + 'em;flex:none';
      var ar = document.createElement('span'); ar.className = 'ck-ar';
      ar.style.cssText = 'display:inline-block;width:1.3em;flex:none;text-align:center;opacity:.75;cursor:pointer';
      var lb = document.createElement('span'); lb.textContent = n.split('::').pop();
      b.appendChild(pad); b.appendChild(ar); b.appendChild(lb);
    });
    list.__ckBtns = btns; list.__ckParents = parents; list.__ckHas = has;
    var act = btns.filter(function(b){ return b.classList.contains('active'); })[0];
    if (act) anc(act.__ckName).forEach(function(a){ open[a] = 1; });
    apply(list);
    if (act) setTimeout(function(){ act.scrollIntoView({block: 'center'}); }, 30);
  }
  // คลิกที่ลูกศร: กาง/พับ และกันไม่ให้เมนูปิด/เลือก
  ['pointerdown','mousedown','mouseup','click'].forEach(function(t){
    window.addEventListener(t, function(e){
      var ar = e.target && e.target.closest && e.target.closest('.ck-ar');
      if (!ar || !ar.textContent) return;
      e.preventDefault(); e.stopImmediatePropagation(); e.stopPropagation();
      if (t !== 'click') return;
      var b = ar.closest('button'), list = b.parentElement;
      while (list && !list.__ckBtns) list = list.parentElement;
      if (!list) return;
      var n = b.__ckName;
      if (open[n]) delete open[n]; else open[n] = 1;
      apply(list); send();
    }, true);
  });
  new MutationObserver(function(){
    document.querySelectorAll('button[role=option]').forEach(function(b){
      var list = b.parentElement;
      if (list && !list.__ckDone) treeify(list);
    });
  }).observe(document.body, {childList: true, subtree: true});
})(%s);
"""


def _import_inject(web):
    try:
        if not on("deck_tree") or web is None:
            return
        import json
        web.page().runJavaScript(_IMPORT_JS % json.dumps(sorted(_opened())))
    except Exception:
        pass


def _patch_import():
    try:
        from aqt.import_export import import_dialog as _id
    except Exception:
        return
    orig = _id.ImportDialog.__init__

    def init(self, *a, **k):
        orig(self, *a, **k)
        try:
            w = self.web
            w.loadFinished.connect(lambda ok, w=w: (_import_inject(w),
                                                    __import__("aqt").qt.QTimer.singleShot(800, lambda: _import_inject(w))))
        except Exception:
            pass

    _id.ImportDialog.__init__ = init


def _on_js(handled, message, context):
    if isinstance(message, str) and message.startswith("ck_dtopen:"):
        try:
            import json
            _save_opened(set(json.loads(message[10:])))
        except Exception:
            pass
        return (True, None)
    return handled


_patch_import()
from aqt import gui_hooks as _gh  # noqa: E402
_gh.webview_did_receive_js_message.append(_on_js)
