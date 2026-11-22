"""
การ์ดคำศัพท์: ด้านหน้า "🏆 Deed : Act, achievement, feat ⏎ …ความหมาย… ⏎ ประโยค" + ด้านหลังว่าง
→ ด้านหน้า = "Deed ⏎⏎⏎⏎ ภาพ+syn"   ด้านหลัง = "🏆 : Act, achievement, feat ⏎ …(บรรทัดที่เหลือเหมือนเดิม)"
ทำตอนออกจากช่องหน้า (เห็นผลทันที) และตอนกด Add
"""

import re

from aqt import gui_hooks

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


def _apply(note):
    if not on("word_split") or len(note.fields) < 2:
        return False
    r = convert(note.fields[0], note.fields[1])
    if not r:
        return False
    note.fields[0], note.fields[1] = r
    return True


def _on_unfocus(changed, note, idx):
    if idx != 0:
        return changed
    try:
        if _apply(note):
            return True                               # ให้ editor โหลดใหม่ เห็นผลทันที
    except Exception:
        pass
    return changed


def _on_add(problem, note):
    try:
        _apply(note)
    except Exception:
        pass
    return problem


gui_hooks.editor_did_unfocus_field.append(_on_unfocus)
gui_hooks.add_cards_will_add_note.append(_on_add)
