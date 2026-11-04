"""
แยกการ์ดหลายข้อ (Multi-Question) ออกเป็นกลุ่มโจทย์/เฉลยตามเลขข้อ
โมดูลนี้ไม่พึ่ง aqt จึงทดสอบนอก Anki ได้
"""

import html as _html
import re

# ป้ายในวงเล็บที่อยู่หน้า "ข้อ" ได้ เช่น (ข้าม) [ทบทวน] ⭐(ยาก) 【สำคัญ】 — สูงสุด 20 ตัวอักษรต่อป้าย
_TAG_PREFIX = r"[^\w]*(?:[(\[【{（][^)\]】}）]{0,20}[)\]】}）][^\w]*)*"
_NUM = r"(\d+(?:\.\d+)*)(?!\d)"          # 22, 22.1, 3.2.1
# เลขข้อแบบไม่มีคำว่า "ข้อ" นำหน้า เช่น "3.4 Helper T cell…", "12. โจทย์…", "5) โจทย์…"
# (ต้องอยู่ต้นบรรทัด ไม่มีวงเล็บนำหน้า กันสับสนกับตัวเลือก "(ก)" หรือค่าตัวเลขในเนื้อหา)
_BARE = r"[^\w(\[]*([1-9]\d*(?:\.\d+)*)(?:[.):]\s*|\s+)(?=[^\d\s.])"
DEFAULT_QUESTION_RE = (
    "^(?:" + _TAG_PREFIX + r"ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*" + _NUM + "|" + _BARE + ")"
    r"(?!\s*:?\s*(?:ตอบ|เฉลย))"
)
DEFAULT_ANSWER_RE = (
    "^" + _TAG_PREFIX + r"(?:เฉลย\s*(?:ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*)?" + _NUM +
    r"|(?:ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*)?" + _NUM + r"\s*[.):：]?\s*(?:ตอบ|เฉลย)"   # "14. ตอบ ง." ก็ได้
    # ไม่มีคำว่า "ตอบ" ก็ได้ ถ้าตามด้วยตัวเลือกทันที เช่น "5. ง.มวล" "5) ข." "ข้อ 5 ค."
    # (คำอธิบายเป็นข้อ ๆ เช่น "1. เพราะ…" ไม่นับ เพราะหลังเลขไม่ใช่ ก–จ)
    r"|(?:ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*)?" + _NUM + r"\s*[.):：]?\s*(?=[(\[]?\s*[กขคงจ]\s*[.)\]]))"
)
DEFAULT_HEADING_RE = r"^[^\w]*T\d+\s*[:：]"

_BOUNDARY = re.compile(
    r"(<br\s*/?>|</?(?:div|p|li|h[1-6]|hr)\b[^>]*>)", re.I
)
_OPENING = re.compile(r"<(?:div|p|li|h[1-6])\b", re.I)
_STYLE_SCRIPT = re.compile(r"<(style|script)\b.*?</\1\s*>", re.I | re.S)
_TAG = re.compile(r"<[^>]+>")
_ANSWER_HR = re.compile(
    r"<hr\b[^>]*\bid\s*=\s*[\"']?answer[\"']?[^>]*>", re.I
)
_VOID = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "source", "track", "wbr",
}


def compile_patterns(cfg=None):
    cfg = cfg or {}

    def c(key, default):
        try:
            return re.compile(cfg.get(key) or default)
        except re.error:
            return re.compile(default)

    return (
        c("mqa_question_regex", DEFAULT_QUESTION_RE),
        c("mqa_answer_regex", DEFAULT_ANSWER_RE),
        c("mqa_heading_regex", DEFAULT_HEADING_RE),
    )


def line_text(fragment: str) -> str:
    s = _STYLE_SCRIPT.sub("", fragment)
    s = _TAG.sub("", s)
    return _html.unescape(s).replace("\xa0", " ").strip()


def balance(fragment: str) -> str:
    """ปิดแท็กที่ค้าง และตัดแท็กปิดที่ไม่มีคู่ เพื่อให้ย้ายไปวางที่อื่นได้ปลอดภัย"""
    out, stack, pos = [], [], 0
    for m in re.finditer(r"<(/?)([a-zA-Z][\w-]*)\b[^>]*?(/?)>", fragment):
        out.append(fragment[pos:m.start()])
        pos = m.end()
        closing, name, selfclose = m.group(1), m.group(2).lower(), m.group(3)
        if closing:
            if name in stack:
                while stack:
                    top = stack.pop()
                    out.append(f"</{top}>")
                    if top == name:
                        break
            # แท็กปิดที่ไม่มีคู่ → ทิ้ง
            continue
        out.append(m.group(0))
        if not selfclose and name not in _VOID:
            stack.append(name)
    out.append(fragment[pos:])
    out.extend(f"</{t}>" for t in reversed(stack))
    return "".join(out)


def normalize_num(g: str) -> str:
    """"22.1" → "22.1", "007" → "7" (เก็บเลขข้อเป็นข้อความ รองรับข้อย่อย)"""
    return ".".join(str(int(x)) for x in g.split(".") if x != "") or g


def _match_num(rx, text):
    m = rx.search(text)
    if not m:
        return None
    for g in m.groups() or ():
        if g:
            return normalize_num(g)
    return ""


_HAS_KHO = re.compile(r"ข้อ|เฉลย|ตอบ")


def segment(fragment: str, marker_re, heading_re, allowed=None):
    """คืน (parts, groups)
    parts  – HTML ที่ถูกตัดเป็นท่อน (ต่อกลับด้วย ''.join(parts) ได้ตามเดิม)
    groups – [{n, start, end, heading}] ช่วง parts[start:end] ของแต่ละข้อ
    """
    parts = _BOUNDARY.split(fragment)
    marks = []  # (index_in_parts, kind, n)
    for i in range(0, len(parts), 2):
        text = line_text(parts[i])
        if not text:
            continue
        n = _match_num(marker_re, text)
        if n and allowed is not None and n not in allowed:
            m = marker_re.search(text)
            if m and not _HAS_KHO.search(text[:m.end()]):
                n = None          # เลขลอย ๆ ที่ไม่มีเฉลยคู่กัน = รายการในเนื้อโจทย์ ไม่ใช่ข้อใหม่
        if n is not None:
            marks.append((i, "q", n))
        elif heading_re.search(text):
            marks.append((i, "h", None))

    def start_of(i):
        if i > 0 and _OPENING.match(parts[i - 1] or ""):
            return i - 1
        return i

    groups, heading = [], ""
    for k, (i, kind, n) in enumerate(marks):
        start = start_of(i)
        end = start_of(marks[k + 1][0]) if k + 1 < len(marks) else len(parts)
        if kind == "h":
            heading = balance("".join(parts[start:end]))
            continue
        groups.append({"n": n, "start": start, "end": end, "heading": heading})
    return parts, groups


_EMPTY = r"(?:\s|<br\s*/?>|&nbsp;|<div>\s*(?:<br\s*/?>)?\s*</div>)"


def _trim_breaks(fragment: str) -> str:
    return re.sub(rf"^{_EMPTY}+|{_EMPTY}+$", "", fragment, flags=re.I)


def answer_map(back_html: str, answer_re, heading_re) -> dict:
    parts, groups = segment(back_html, answer_re, heading_re)
    out = {}
    for g in groups:
        frag = _trim_breaks(balance("".join(parts[g["start"]:g["end"]])))
        if g["n"] and frag:
            out.setdefault(g["n"], frag)
    return out


def split_answer_side(answer_html: str):
    """แยกหน้าเฉลยที่ Anki render แล้ว เป็น (ส่วนโจทย์, ส่วนเฉลย) ตาม <hr id=answer>"""
    m = _ANSWER_HR.search(answer_html)
    if not m:
        return None
    return answer_html[:m.start()], answer_html[m.end():]


# ------------------------------------------------------------------ features

_CHOICE = re.compile(r"^[^\wก-ฮ]*([กขคงจ])\s*[.)]")
_CORRECT = re.compile(
    r"(?:ตอบ|เฉลย)\s*[:：]?\s*(?:(?:ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*)?\d[\d.]*\s*[:：]?\s*(?:ตอบ\s*[:：]?\s*)?)?"
    r"[(\[]?\s*([กขคงจ])\s*[.)\]]")


_CORRECT_BARE = re.compile(
    r"^[^\w]*(?:ข้อ(?:หลัก|ย่อย)?(?:ที่)?\s*)?\d+(?:\.\d+)*\s*[.):：]?\s*[(\[]?\s*([กขคงจ])\s*[.)\]]")


_CORRECT_ONLY = re.compile(r"^[^\w]*[(\[]?\s*([กขคงจ])\s*[.)\]]")


def _single_answer(question_html, back_html, q_re, h_re):
    """การ์ดข้อเดียว + เฉลยไม่มีเลขข้อ เช่น ด้านหลังขึ้นต้นว่า "ข.คา ไฟ"
    → ใช้เฉลยนี้กับข้อนั้น (เฉพาะเมื่อด้านหน้ามีโจทย์ข้อเดียว และบรรทัดแรกของเฉลยขึ้นต้นด้วย ก–จ)"""
    parts, groups = segment(question_html, q_re, h_re)

    def n_choices(g):
        return sum(1 for j in range(g["start"], g["end"])
                   if j % 2 == 0 and _CHOICE.match(line_text(parts[j])))
    # นับเฉพาะ "ข้อ" ที่มีตัวเลือก ก–ง อย่างน้อย 2 ตัว (เลขในคำอธิบาย เช่น "🎭 4. …" ไม่นับ)
    groups = [g for g in groups if g["n"] and n_choices(g) >= 2]
    if len(groups) != 1:
        return {}
    first = next((line_text(x) for x in _BOUNDARY.split(back_html)[::2] if line_text(x)), "")
    if not _CORRECT_ONLY.match(first):
        return {}
    return {groups[0]["n"]: _trim_breaks(back_html)}


def correct_choice(answer_html: str):
    """อ่านตัวเลือกที่ถูกจากเฉลย เช่น "✅ ข้อ 98 ตอบ ข. …" หรือ "5. ง.มวล" → "ข" / "ง" """
    t = line_text(answer_html)
    m = _CORRECT.search(t) or _CORRECT_BARE.search(t) or _CORRECT_ONLY.search(t)
    return m.group(1) if m else ""


def inline_reveal(question_html: str, back_html: str, patterns,
                  show_buttons=True, stealth=False, click_choice=False,
                  accordion=False, key="", compact=False, noframe=False) -> str:
    q_re, a_re, h_re = patterns
    answers = answer_map(back_html, a_re, h_re)
    if not answers:
        answers = _single_answer(question_html, back_html, q_re, h_re)
    if not answers:
        return question_html
    parts, groups = segment(question_html, q_re, h_re, allowed=set(answers))
    targets = [g for g in groups if g["n"] in answers]
    if not targets:
        return question_html

    cls = "mqa-slot"
    if not show_buttons:
        cls += " mqa-nobtn"
    elif stealth:
        cls += " mqa-stealth"
    if accordion:
        cls += " mqa-accordion"

    inserts = []
    for idx, g in enumerate(targets, start=1):
        ans = answers[g["n"]]
        right = correct_choice(ans)
        if click_choice:
            # ห่อบรรทัด ก./ข./ค./ง. ให้คลิกตอบได้ (แก้ใน parts ตรง ๆ ไม่เปลี่ยนตำแหน่ง)
            for j in range(g["start"], g["end"]):
                if j % 2:
                    continue
                m = _CHOICE.match(line_text(parts[j]))
                if m:
                    c = m.group(1)
                    parts[j] = (f'<span class="mqa-choice{" mqa-nf" if noframe else ""}" data-i="{idx}" data-c="{c}" '
                                f'onclick="mqaPick({idx},\'{c}\')">{parts[j]}</span>')
        # เลขบนคีย์บอร์ด (แยกจาก key = id การ์ด ที่ใช้จำคำตอบ — เดิมใช้ชื่อเดียวกันจนคำตอบข้ามไปการ์ดอื่น)
        digit = str(idx % 10) if idx <= 10 else ""
        key_html = (f'<span class="mqa-key" title="กดเลขนี้บนคีย์บอร์ดเพื่อเปิด/ปิด">กด {digit}</span>'
                    if digit and show_buttons else "")
        block = (
            f'<div class="{cls}" data-i="{idx}" data-n="{g["n"]}" data-ans="{right}" data-k="{key}">'
            f'<div class="mqa-btn" onclick="mqaToggle({idx})">'
            f"▸ เฉลยข้อ {g['n']}{key_html}</div>"
            f'<div class="mqa-ans">{compact_answer(ans, emoji_only=click_choice) if compact else ans}</div></div>'
        )
        inserts.append((_content_end(parts, g), block))
        inserts.append((g["start"], f'<span class="mqa-q" data-i="{idx}" data-n="{g["n"]}"></span>'))
    for pos, block in sorted(inserts, key=lambda x: x[0], reverse=True):
        parts.insert(pos, block)
    return "".join(parts)


_CLOSING = re.compile(r"</(?:div|p|li|h[1-6])\s*>", re.I)


def _content_end(parts, g):
    """ตำแหน่งต่อจากบรรทัดสุดท้ายที่มีเนื้อหาของข้อนั้น (ข้ามบรรทัดว่างท้ายข้อ)"""
    last = None
    for j in range(g["start"], g["end"]):
        if j % 2 == 0 and line_text(parts[j]):
            last = j
    if last is None:
        return g["end"]
    pos = last + 1
    if pos < g["end"] and _CLOSING.fullmatch(parts[pos] or ""):
        pos += 1
    return pos


def split_fields(front_field: str, back_field: str, patterns):
    """แยกฟิลด์โจทย์/เฉลยของโน้ตหนึ่งใบ เป็นรายการ (front, back) ทีละข้อ"""
    q_re, a_re, h_re = patterns
    answers = answer_map(back_field, a_re, h_re)
    parts, groups = segment(front_field, q_re, h_re, allowed=set(answers))
    pieces = []
    for g in groups:
        q = _trim_breaks(balance("".join(parts[g["start"]:g["end"]])))
        if not q:
            continue
        head = _trim_breaks(g["heading"])
        if head:
            sep = "" if head.lower().endswith("</div>") else "<br><br>"
            front = f"{head}{sep}{q}"
        else:
            front = q
        pieces.append((g["n"], front, answers.get(g["n"], "")))
    return pieces


def remove_questions(front_field: str, back_field: str, nums, patterns):
    """ตัดโจทย์ (ช่องแรก) และเฉลย (ช่องที่สอง) ของข้อที่อยู่ใน nums ออก
    คืน (front ใหม่, back ใหม่) — ส่วนอื่นของการ์ดคงเดิม"""
    q_re, a_re, h_re = patterns
    nums = set(nums)

    known = set(answer_map(back_field, a_re, h_re))

    def cut(fragment, marker_re):
        parts, groups = segment(fragment, marker_re, h_re,
                                allowed=known if marker_re is q_re else None)
        spans = [(g["start"], g["end"]) for g in groups if g["n"] in nums]
        if not spans:
            return fragment
        for start, end in sorted(spans, reverse=True):
            del parts[start:end]
        return _trim_breaks(balance("".join(parts)))

    return cut(front_field, q_re), cut(back_field, a_re)


def mark_questions(fragment: str, patterns, back_html: str = "") -> str:
    """ใส่จุดอ้างอิงที่มองไม่เห็นหน้าแต่ละข้อ (ใช้ไฮไลต์ข้อบนการ์ดตอนเปิดแผงย้ายข้อ)"""
    q_re, a_re, h_re = patterns
    allowed = set(answer_map(back_html, a_re, h_re)) if back_html else None
    parts, groups = segment(fragment, q_re, h_re, allowed=allowed)
    if not groups:
        return fragment
    for g in sorted(groups, key=lambda g: g["start"], reverse=True):
        parts.insert(g["start"], f'<span class="mqa-q" data-n="{g["n"]}"></span>')
    return "".join(parts)


def compact_answer(ans_html: str, emoji_only=False) -> str:
    """ย่อกล่องเฉลย
    emoji_only=True (ใช้คู่กับคลิกตอบ ซึ่งตัวเลือกที่ถูกมีกรอบเขียวอยู่แล้ว): เหลือแค่ 💡 กดแล้วกางเฉลยเต็ม
    emoji_only=False: แสดงบรรทัดคำตอบ ส่วนคำอธิบายพับไว้ใต้ ▸ อ่านคำอธิบาย"""
    if emoji_only:
        return (f'<details class="mqa-more mqa-emoji"><summary title="อ่านคำอธิบาย">💡</summary>'
                f'<div class="mqa-exp">{ans_html}</div></details>')
    parts = _BOUNDARY.split(ans_html)
    first = next((j for j in range(0, len(parts), 2) if line_text(parts[j])), None)
    if first is None:
        return ans_html
    cut = first + 1
    if cut < len(parts) and _CLOSING.fullmatch(parts[cut] or ""):
        cut += 1
    head = balance("".join(parts[:cut]))
    rest = _trim_breaks(balance("".join(parts[cut:])))
    if not line_text(rest):
        return ans_html
    return (f'<div class="mqa-ans-head">{head}</div>'
            f'<details class="mqa-more"><summary><span class="o">▸ อ่านคำอธิบาย</span>'
            f'<span class="c">▾ ซ่อนคำอธิบาย</span></summary>{rest}</details>')
