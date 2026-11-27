"""
แถบด้านบน (Decks Add Browse Stats Sync): ระหว่างท่อง ชี้เมาส์ทางขวาของ Sync → ปุ่ม "📅 Set Due Date ⇧⌘D" โผล่
กด = Set Due Date ของการ์ดที่กำลังท่อง (เหมือน More → Set Due Date / Ctrl+Shift+D)
"""

import sys

from aqt import gui_hooks, mw

from .mqa import on

_ID = "ck-due"
_CSS = (f"<style>#{_ID}{{opacity:0;transition:opacity .15s ease;background:none!important;"
        f"border:none!important;box-shadow:none!important;outline:none!important;padding:0 4px!important;"
        f"white-space:nowrap;z-index:50;color:var(--fg,#e6e6e6)!important;text-decoration:none!important;"
        f"font-weight:600;cursor:pointer}}"
        f"#{_ID}:hover{{opacity:1;background:none!important}}</style>")
# ย้ายปุ่มออกจากแถบ ไปลอยข้าง Sync (ไม่ยืดแถบ ไม่มีกรอบ)
_PLACE_JS = f"""<script>(function(){{
  function place(){{
    var e = document.getElementById('{_ID}'); if (!e) return;
    var hs = document.querySelectorAll('.hitem'), s = document.getElementById('sync');
    if (!s) {{ for (var i = hs.length - 1; i >= 0; i--) if (hs[i] !== e) {{ s = hs[i]; break; }} }}
    if (!s) return;
    if (e.parentNode !== document.body) document.body.appendChild(e);
    var r = s.getBoundingClientRect();
    e.style.position = 'fixed'; e.style.left = (r.right + 16) + 'px'; e.style.top = r.top + 'px';
    e.style.height = r.height + 'px'; e.style.lineHeight = r.height + 'px'; e.style.display = e.style.display;
  }}
  place(); setTimeout(place, 100); setTimeout(place, 600);
  window.addEventListener('resize', place);
}})();</script>"""


def _click():
    if mw.state != "review" or getattr(mw.reviewer, "card", None) is None:
        return
    try:
        mw.reviewer.on_set_due()
    except Exception as e:
        print("[cardkit due btn]", e)


def _show_js(show):
    return (f"(function(){{var e=document.getElementById('{_ID}');"
            f"if(e) e.style.display='{'' if show else 'none'}';}})();")


def _on_links(links, toolbar):
    if not on("due_button"):
        return
    try:
        keys = "⇧⌘D" if sys.platform == "darwin" else "Ctrl+Shift+D"
        label = "📅 Set Due Date"
        html = toolbar.create_link("ck_set_due", label, _click,
                                   tip=f"Set Due Date ของการ์ดนี้ ({keys})", id=_ID)
        html = html.replace(f">{label}</a>", f">{label}<span style=\"opacity:.5;font-size:.8em;"
                                              f"margin-left:8px\">{keys}</span></a>", 1)
        if mw.state != "review":
            html = html.replace("<a ", '<a style="display:none" ', 1)
        links.append(_CSS + html + _PLACE_JS)
    except Exception as e:
        print("[cardkit due btn]", e)


def _on_state(new, _old):
    try:
        mw.toolbar.web.eval(_show_js(new == "review" and on("due_button")))
    except Exception:
        pass


gui_hooks.top_toolbar_did_init_links.append(_on_links)
gui_hooks.state_did_change.append(_on_state)
