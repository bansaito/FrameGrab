"""
แถบด้านบน (Decks Add Browse Stats Sync): ระหว่างท่อง ชี้เมาส์ทางขวาของ Sync → ปุ่ม "📅 ตั้งวันทวน" โผล่
กด = Set Due Date ของการ์ดที่กำลังท่อง (เหมือน More → Set Due Date / Ctrl+Shift+D)
"""

from aqt import gui_hooks, mw

from .mqa import on

_ID = "ck-due"
_CSS = (f"<style>#{_ID}{{opacity:0;transition:opacity .15s ease;margin-left:6px}}"
        f"#{_ID}:hover{{opacity:1}}</style>")


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
        html = toolbar.create_link("ck_set_due", "📅 ตั้งวันทวน", _click,
                                   tip="Set Due Date ของการ์ดนี้ (Ctrl+Shift+D)", id=_ID)
        if mw.state != "review":
            html = html.replace("<a ", '<a style="display:none" ', 1)
        links.append(_CSS + html)
    except Exception as e:
        print("[cardkit due btn]", e)


def _on_state(new, _old):
    try:
        mw.toolbar.web.eval(_show_js(new == "review" and on("due_button")))
    except Exception:
        pass


gui_hooks.top_toolbar_did_init_links.append(_on_links)
gui_hooks.state_did_change.append(_on_state)
