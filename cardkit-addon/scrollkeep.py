"""
หน้า Decks: จำตำแหน่งเลื่อนล่าสุด → กลับมาหน้านี้ (หลังท่อง / กด ➕ ในปุ่มลอย / refresh) อยู่ที่เดิม
จำเฉพาะระหว่างเปิด Anki อยู่ (ปิดแล้วเปิดใหม่ = เริ่มบนสุด)
"""

from aqt import gui_hooks

from .mqa import on

_st = {"y": 0}

_JS = r"""
(function(y){
  if (!window.__ckDbSy) {
    window.__ckDbSy = 1;
    var tm = null;
    window.addEventListener('scroll', function(){
      clearTimeout(tm);
      tm = setTimeout(function(){
        if (window.__ckDbReady) pycmd('ck_dbsy:' + Math.round(window.scrollY));
      }, 200);
    }, {passive: true});
  }
  // ระหว่างเลื่อนคืน ไม่รายงานค่า (กันค่า 0 ตอนวาดหน้าใหม่) จนกว่าผู้ใช้จะแตะ/เลื่อนเอง
  window.__ckDbReady = false;
  setTimeout(function(){ window.__ckDbReady = true; }, 1100);
  var moved = false;
  ['wheel','keydown','mousedown','touchstart'].forEach(function(ev){
    window.addEventListener(ev, function(){ moved = true; window.__ckDbReady = true; }, {capture: true, once: true});
  });
  if (y <= 0) return;
  [0, 60, 200, 500, 1000].forEach(function(t){
    setTimeout(function(){ if (!moved && Math.abs(window.scrollY - y) > 2) window.scrollTo(0, y); }, t);
  });
})(%d);
"""


def _on_render(deck_browser):
    if not on("deck_scroll"):
        return
    try:
        deck_browser.web.eval(_JS % int(_st["y"]))
    except Exception:
        pass


def _on_msg(handled, message, context):
    if not isinstance(message, str) or not message.startswith("ck_dbsy:"):
        return handled
    try:
        _st["y"] = int(message[8:])
    except ValueError:
        pass
    return (True, None)


gui_hooks.deck_browser_did_render.append(_on_render)
gui_hooks.webview_did_receive_js_message.append(_on_msg)
