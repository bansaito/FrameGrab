"""
การ์ดคำศัพท์: ด้านหน้า "🏆 Deed : Act, achievement, feat ⏎ …ความหมาย… ⏎ ประโยค" + ด้านหลังว่าง
→ ด้านหน้า = "Deed ⏎⏎⏎⏎ ภาพ+syn"   ด้านหลัง = "🏆 : Act, achievement, feat ⏎ …(บรรทัดที่เหลือเหมือนเดิม)"
ทำตอนออกจากช่องหน้า (เห็นผลทันที) และตอนกด Add
"""

import re
import time
import weakref

from aqt import gui_hooks
from aqt.qt import QTimer

from .mqa import cfg, on

DEFAULT_TAIL = "<br><br><br><br><br>ภาพ+syn<br><br><br>"

# บรรทัดแรก: [แท็ก html] [อีโมจิ/สัญลักษณ์] คำอังกฤษ : ความหมาย
_FIRST = re.compile(
    r"^(?P<lead>(?:\s|<[^>]+>|&nbsp;)*)"
    r"(?P<emo>(?:[^\w\s<&:]|&nbsp;|\s)*?)"
    r"(?P<word>[A-Za-z][A-Za-z'’\- ]{0,40}?)"
    r"(?P<sp>(?:\s|&nbsp;)*):(?P<rest>.*)$", re.S)
_SPLIT = re.compile(r"(<br\s*/?>|</div>)", re.I)


def _plain(html):
    return re.sub(r"<[^>]+>|&nbsp;", "", html or "").strip()


def convert(front, back):
    """คืน (หน้าใหม่, หลังใหม่) หรือ None ถ้าไม่ตรงรูปแบบ"""
    if _plain(back):
        return None
    parts = _SPLIT.split(front or "", maxsplit=1)
    first = parts[0]
    after = "".join(parts[1:])
    if not _plain(after):
        return None                                   # ต้องมีบรรทัดความหมาย/ประโยคต่อท้าย
    m = _FIRST.match(first)
    if not m or not m.group("emo").strip("  ").replace("&nbsp;", "").strip():
        return None                                   # ต้องมีอีโมจินำหน้า (กันไปแปลงการ์ดอื่น)
    word = m.group("word").strip()
    if not word:
        return None
    emo = m.group("emo").replace("&nbsp;", " ").strip()
    new_back = f'{m.group("lead")}{emo} :{m.group("rest")}{after}'
    tail = cfg().get("word_split_front_tail", DEFAULT_TAIL)
    new_front = f"{word}{tail}"
    return new_front, new_back


# ด้านหลังที่เพิ่งแยกให้ — ถ้าช่องหลังถูกเขียนทับด้วยค่าว่าง (ตอนเคอร์เซอร์อยู่ในช่องหลัง Anki ไม่โหลดช่องนั้นใหม่
# พอคลิกออกก็ส่งค่าว่างกลับมาทับ) → ใส่คืนให้
_pending = {}            # id(note) → (ด้านหลัง, เวลา)
_editors = weakref.WeakSet()


def _apply(note):
    if not on("word_split") or len(note.fields) < 2:
        return False
    r = convert(note.fields[0], note.fields[1])
    if not r:
        return False
    note.fields[0], note.fields[1] = r
    _pending[id(note)] = (r[1], time.time())
    return True


def _restore(note):
    p = _pending.get(id(note))
    if not p or time.time() - p[1] > 600:
        return False
    if _plain(note.fields[1]):
        _pending.pop(id(note), None)                 # ผู้ใช้พิมพ์ด้านหลังเองแล้ว → เลิกยุ่ง
        return False
    note.fields[1] = p[0]
    return True


def _refresh_editor(note):
    """ช่องหลังที่โฟกัสอยู่ไม่ถูกโหลดใหม่ → ปลดโฟกัสก่อน (จะเกิด blur → ใส่คืน → โหลดใหม่)"""
    for ed in list(_editors):
        try:
            if ed.note is note and ed.web is not None:
                ed.web.eval("document.activeElement && document.activeElement.blur && document.activeElement.blur();"
                            "var a=document.activeElement; try{ (a&&a.shadowRoot&&a.shadowRoot.activeElement||a).blur(); }catch(e){}")
        except Exception:
            pass


def _on_unfocus(changed, note, idx):
    try:
        if idx == 0 and _apply(note):
            QTimer.singleShot(250, lambda n=note: _refresh_editor(n))
            return True                               # ให้ editor โหลดใหม่ เห็นผลทันที
        if idx == 1 and _restore(note):
            return True
    except Exception:
        pass
    return changed


def _on_add(problem, note):
    try:
        if not _apply(note):
            _restore(note)
        _pending.pop(id(note), None)
    except Exception:
        pass
    return problem


def _on_editor_init(ed):
    _editors.add(ed)


gui_hooks.editor_did_init.append(_on_editor_init)


gui_hooks.editor_did_unfocus_field.append(_on_unfocus)
gui_hooks.add_cards_will_add_note.append(_on_add)
