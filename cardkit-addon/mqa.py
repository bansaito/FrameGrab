"""
การ์ดหลายข้อ (Multi-Question)
  • เฉลยใต้แต่ละข้อ  – หน้าโจทย์: กดปุ่มหรือกดเลข 1–9, 0 เพื่อเปิดเฉลยข้อนั้นตรงที่โจทย์
  • แยกเป็นข้อละใบ   – ปุ่มในหน้า Add และเมนูใน Browse
ทุกอย่างปิดไว้เป็นค่าเริ่มต้น เปิดได้จากเมนู "🃏 CardKit" ถัดจาก Help
"""

import time

from aqt import gui_hooks, mw

from . import keymap
from aqt.addcards import AddCards
from aqt.qt import (
    QAction, QApplication, QCheckBox, QCursor, QDialog, QDialogButtonBox, QEvent, QHBoxLayout,
    QKeySequence, QObject,
    QLabel, QListWidget, QListWidgetItem, QMenu, QPushButton, QSplitter, Qt,
    QTimer, QVBoxLayout, QWidget,
)
from aqt.utils import askUser, showInfo, tooltip

from . import mqa_parse as P

ADDON = __name__.split(".")[0]

KEYS = {"inline": "mqa_inline", "inline_stealth": "mqa_inline_stealth",
        "inline_hidebtn": "mqa_inline_hidebtn",
        "inline_accordion": "mqa_inline_accordion",
        "click_choice": "mqa_click_choice", "choice_noframe": "mqa_choice_noframe",
        "compact_answer": "mqa_compact_answer",
        "split_tool": "mqa_split_tool",
        "hover_open": "menu_hover_open",
        "keep_scroll": "keep_scroll_after_edit",
        "edit_here": "edit_open_at_reading",
        "mouse_back": "mouse_back_button", "mouse_mid_quote": "mouse_middle_quote"}

TAG_NEW = "แยกข้อ::ใหม่"
TAG_ORIG = "แยกข้อ::ต้นฉบับ"


# ---------------------------------------------------------------- config

def cfg():
    try:
        return mw.addonManager.getConfig(ADDON) or {}
    except Exception:
        return {}


# ค่าเริ่มต้นที่เปิดไว้ (เครื่องที่ติดตั้งใหม่ / ยังไม่เคยตั้งเอง)
DEFAULT_ON = {"inline", "inline_stealth", "inline_hidebtn", "inline_accordion",
              "click_choice", "choice_noframe", "hover_open", "keep_scroll", "edit_here",
              "mouse_back", "mouse_mid_quote"}


def on(feature):
    return bool(cfg().get(KEYS[feature], feature in DEFAULT_ON))


def patterns():
    return P.compile_patterns(cfg())


def _prep(text):
    fn = getattr(mw, "prepare_card_text_for_display", None)
    try:
        return fn(text) if fn else text
    except Exception:
        return text


# ---------------------------------------------------------------- reviewer

CSS_JS = r"""
<style>
  .mqa-slot { margin: 6px auto 4px; max-width: 46em; }
  .mqa-q { display: inline-block; width: 0; height: 0; overflow: hidden; vertical-align: top; }
  /* เริ่มต้นจาง → ผ่านไป 3 วินาทีค่อย ๆ ล่องหน → ชี้เมาส์ที่ข้อนั้นถึงโผล่ */
  .mqa-btn { display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
    user-select: none; font-size: .72em; padding: 2px 8px; border-radius: 6px;
    opacity: .35; animation: mqaFadeOut .8s ease 3s forwards;
    transition: opacity 1.2s ease; }
  @keyframes mqaFadeOut { from { opacity: .35; } to { opacity: 0; } }
  .mqa-nobtn .mqa-btn { display: none; }
  .mqa-stealth .mqa-btn { animation: none; opacity: 0; }
  .mqa-btn.gone { animation: none; opacity: 0; }
  .mqa-slot.peek .mqa-btn { animation: none; opacity: .75; transition: opacity .15s ease; }
  .mqa-btn:hover { animation: none; opacity: 1; background: rgba(128,128,128,.15);
    transition: opacity .15s ease; }
  .mqa-slot.open .mqa-btn { animation: none; opacity: .45; }
  .mqa-key { font: 600 .85em ui-monospace, Consolas, monospace; padding: 0 5px; opacity: .8;
    border-radius: 4px; background: rgba(128,128,128,.25); }
  .mqa-ans { display: none; margin: 4px 0 10px; padding: 10px 14px; text-align: inherit;
    border-left: 3px solid #22c55e; border-radius: 6px; background: rgba(34,197,94,.09); }
  .mqa-slot.open .mqa-ans { display: block; animation: mqaIn .18s ease-out; }
  .mqa-more > summary { list-style: none; cursor: pointer; user-select: none; display: inline-block;
    margin-top: 6px; font-size: .78em; opacity: .6; padding: 1px 8px; border-radius: 6px; }
  .mqa-more > summary::-webkit-details-marker { display: none; }
  .mqa-more > summary:hover { opacity: 1; background: rgba(128,128,128,.15); }
  .mqa-more > summary .c, .mqa-more[open] > summary .o { display: none; }
  .mqa-more[open] > summary .c { display: inline; }
  .mqa-more[open] { animation: mqaIn .18s ease-out; }
  /* โหมด 💡 : ไม่มีกล่องเขียว เหลือแค่อีโมจิ กดแล้วกางเฉลยเต็ม */
  .mqa-ans:has(> .mqa-emoji) { background: none; border: none; padding: 0; margin: 2px 0 6px; }
  .mqa-emoji > summary { font-size: 1.05em; opacity: .75; padding: 2px 10px; margin-top: 0; }
  .mqa-emoji[open] > summary { opacity: .45; }
  .mqa-exp { margin-top: 6px; padding: 10px 14px; border-left: 3px solid #22c55e; border-radius: 6px;
    background: rgba(34,197,94,.09); }
  @keyframes mqaIn { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; } }

  /* คลิกตัวเลือกเพื่อตอบ */
  .mqa-choice { display: inline-block; cursor: pointer; padding: 2px 10px; border-radius: 8px;
    transition: background .15s ease; }
  .mqa-choice:not(.locked):hover { background: rgba(128,128,128,.16); }
  .mqa-choice.locked { cursor: pointer; }
  .mqa-choice.right { background: rgba(34,197,94,.22); box-shadow: inset 0 0 0 1px rgba(34,197,94,.65); }
  .mqa-choice.right::after { content: " ✓"; color: #22c55e; font-weight: 700; }
  .mqa-choice.wrong { background: rgba(239,68,68,.2); box-shadow: inset 0 0 0 1px rgba(239,68,68,.65); }
  .mqa-choice.wrong::after { content: " ✗"; color: #ef4444; font-weight: 700; }
  .mqa-choice.picked { box-shadow: inset 0 0 0 1px rgba(128,128,128,.7); }
  /* ไม่มีกรอบสี: เหลือแค่อีโมจิ */
  .mqa-choice.mqa-nf.right, .mqa-choice.mqa-nf.wrong, .mqa-choice.mqa-nf.picked { background: none; box-shadow: none; }
  .mqa-choice.mqa-nf.right::after { content: " ✅"; }
  .mqa-choice.mqa-nf.wrong::after { content: " ❌"; }
  /* คลิกซ้ำ: ซ่อนผลของข้อนั้นทั้งหมด */
  .mqa-choice.off.right, .mqa-choice.off.wrong, .mqa-choice.off.picked { background: none; box-shadow: none; }
  .mqa-choice.off.right::after, .mqa-choice.off.wrong::after { content: none; }
  .mqa-slot.mqa-off .mqa-ans { display: none; }
  .mqa-ans { cursor: pointer; }

</style>
<script>
  function mqaPicks() {
    var o = {};
    document.querySelectorAll(".mqa-slot").forEach(function (s) {
      o[s.getAttribute("data-n")] = { picked: s.getAttribute("data-picked") || "",
                                      ans: s.getAttribute("data-ans") || "" };
    });
    return JSON.stringify(o);
  }
  document.addEventListener("toggle", function (e) {   // พิมพ์สูตรในคำอธิบายที่เพิ่งกางออก
    var d = e.target;
    if (d.classList && d.classList.contains("mqa-more") && d.open && window.MathJax && MathJax.typesetPromise)
      MathJax.typesetPromise([d]).catch(function () {});
  }, true);
  window.mqaState = window.mqaState || {};   // คำตอบที่เลือกไว้ แยกตามการ์ด (อยู่ข้าม Show Answer)
  function mqaPick(i, c) {
    if (window.getSelection && String(window.getSelection())) return;  // กำลังลากเลือกข้อความ
    var s = document.querySelector('.mqa-slot[data-i="' + i + '"]');
    if (!s) return;
    if (s.getAttribute("data-picked")) {           // ตอบแล้ว → คลิกซ้ำ = ซ่อน/แสดงผลทั้งหมดของข้อนี้
      var off = !s.classList.contains("mqa-off");
      if (!off) mqaCollapseOthers(s);               // แสดงผลข้อนี้กลับมา = นับเป็นการเปิดข้อใหม่
      mqaSetOff(s, off);
      return;
    }
    var k = s.getAttribute("data-k");
    // จำตามเลขข้อ (data-n) ไม่ใช่ลำดับ — แก้/ลบข้อใน Edit แล้วคำตอบไม่เลื่อนไปข้ออื่น
    if (k) (mqaState[k] = mqaState[k] || {})[s.getAttribute("data-n")] = c;
    mqaCollapseOthers(s);
    mqaMark(s, i, c);
    if (!s.classList.contains("open")) mqaToggle(i);
  }
  function mqaMark(s, i, c) {
    s.setAttribute("data-picked", c);
    var right = s.getAttribute("data-ans");
    document.querySelectorAll('.mqa-choice[data-i="' + i + '"]').forEach(function (el) {
      el.classList.add("locked");
      var k = el.getAttribute("data-c");
      if (right && k === right) el.classList.add("right");
      else if (k === c) el.classList.add(right ? "wrong" : "picked");
    });
  }
  document.addEventListener("click", function (e) {
    var t = e.target;
    if (!t.closest || t.closest("summary,a,button,input,.mqa-btn")) return;
    var ans = t.closest(".mqa-ans");
    if (!ans) return;
    if (window.getSelection && String(window.getSelection())) return;   // กำลังลากเลือกข้อความ
    var s = ans.closest(".mqa-slot"), i = s && s.getAttribute("data-i");
    if (!s) return;
    if (s.getAttribute("data-picked")) mqaPick(i, s.getAttribute("data-picked"));
    else mqaToggle(i);
  });
  function mqaRestore() {
    document.querySelectorAll(".mqa-slot[data-k]:not([data-restored])").forEach(function (s) {
      s.setAttribute("data-restored", "1");
      var st = mqaState[s.getAttribute("data-k")], i = s.getAttribute("data-i"), n = s.getAttribute("data-n");
      if (st && st[n] && !s.getAttribute("data-picked")) {
        mqaMark(s, i, st[n]);
        s.classList.add("open");
      }
    });
  }
  new MutationObserver(mqaRestore).observe(document.documentElement, { childList: true, subtree: true });
  function mqaSetOff(s, off) {
    var i = s.getAttribute("data-i");
    s.classList.toggle("mqa-off", off);
    document.querySelectorAll('.mqa-choice[data-i="' + i + '"]').forEach(function (el) {
      el.classList.toggle("off", off);
    });
    if (off) { var d = s.querySelector("details.mqa-more"); if (d) d.open = false; }
  }
  function mqaCollapseOthers(s) {
    // ปิดเฉลยเก่าอัตโนมัติ: ข้ออื่นกลับเป็นโจทย์สะอาด (ซ่อนทั้งเฉลยและกรอบถูก/ผิด)
    // แล้วเลื่อนจอชดเชย ให้ข้อที่กำลังทำอยู่ตำแหน่งเดิม
    if (!s.classList.contains("mqa-accordion")) return;
    var before = s.getBoundingClientRect().top;
    document.querySelectorAll(".mqa-slot").forEach(function (o) {
      if (o === s) return;
      if (o.getAttribute("data-picked")) { if (!o.classList.contains("mqa-off")) mqaSetOff(o, true); }
      else o.classList.remove("open");
    });
    var shift = s.getBoundingClientRect().top - before;
    if (shift) window.scrollBy(0, shift);
  }
  function mqaToggle(i) {
    var s = document.querySelector('.mqa-slot[data-i="' + i + '"]');
    if (!s) return false;
    if (!s.classList.contains("open")) mqaCollapseOthers(s);
    s.classList.toggle("open");
    if (s.classList.contains("open")) {
      var a = s.querySelector(".mqa-ans");
      if (window.MathJax && MathJax.typesetPromise) {
        MathJax.typesetPromise([a]).catch(function () {});
      }
      s.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
    return true;
  }
  // ชี้เมาส์ส่วนใดของข้อก็ได้ (โจทย์/ตัวเลือก/ปุ่ม) → ปุ่มเฉลยของข้อนั้นโผล่
  var mqaPeek = null;
  function mqaSetPeek(i) {
    if (mqaPeek === i) return;
    mqaPeek = i;
    document.querySelectorAll(".mqa-slot.peek").forEach(function (s) {
      s.classList.remove("peek");
      var b = s.querySelector(".mqa-btn");
      if (b) b.classList.add("gone");
    });
    if (i) {
      var s = document.querySelector('.mqa-slot[data-i="' + i + '"]');
      if (s) s.classList.add("peek");
    }
  }
  document.addEventListener("animationend", function (e) {
    if (e.animationName === "mqaFadeOut") e.target.classList.add("gone");
  });
  // ขอบเขตของข้อ = กรอบรอบโจทย์ ตัวเลือก จนถึงปุ่ม/เฉลย (ไม่รวมช่องว่างระหว่างข้อ)
  // เมาส์ออกจากกรอบ → ปุ่มเริ่มจางทันที
  var mqaX = null, mqaY = null, mqaRaf = 0;
  function mqaRegion(i) {
    var mark = document.querySelector('.mqa-q[data-i="' + i + '"]');
    var slot = document.querySelector('.mqa-slot[data-i="' + i + '"]');
    if (!mark || !slot) return null;
    var r = document.createRange();
    r.setStartBefore(mark);
    // เฉลยเปิดอยู่ → นับรวมกล่องเฉลย, ยังไม่เปิด → นับถึงแค่ตัวปุ่ม
    r.setEndAfter(slot.classList.contains("open") ? slot : slot.querySelector(".mqa-btn"));
    return r.getBoundingClientRect();
  }
  function mqaTrack() {
    mqaRaf = 0;
    var hit = null;
    if (mqaY !== null) {
      var marks = document.querySelectorAll(".mqa-q"), pad = 14;
      for (var k = 0; k < marks.length; k++) {
        var i = marks[k].getAttribute("data-i"), b = mqaRegion(i);
        if (b && mqaX >= b.left - pad && mqaX <= b.right + pad &&
            mqaY >= b.top - pad && mqaY <= b.bottom + pad) { hit = i; break; }
      }
    }
    mqaSetPeek(hit);
  }
  document.addEventListener("mousemove", function (e) {
    mqaX = e.clientX; mqaY = e.clientY;
    if (!mqaRaf) mqaRaf = requestAnimationFrame(mqaTrack);
  });
  document.addEventListener("scroll", function () {
    if (mqaY !== null && !mqaRaf) mqaRaf = requestAnimationFrame(mqaTrack);
  }, true);
  document.documentElement.addEventListener("mouseleave", function () { mqaX = mqaY = null; mqaSetPeek(null); });
</script>
"""


def _is_card_view(context):
    names = {"Reviewer", "Previewer", "BrowserPreviewer", "MultiCardPreviewer",
             "SingleCardPreviewer"}
    return any(c.__name__ in names for c in type(context).__mro__)


def on_set_content(web_content, context):
    if _is_card_view(context):
        web_content.head += CSS_JS + MOVE_CSS_JS


def on_card_will_show(text, card, kind):
    try:
        opts = dict(
            show_buttons=on("inline") and not on("inline_hidebtn"),
            stealth=on("inline_stealth"),
            click_choice=on("click_choice"),
            accordion=on("inline") and on("inline_accordion"),
            key=str(card.id),
            compact=on("compact_answer"),
            noframe=on("click_choice") and on("choice_noframe"),
        )
        pat = patterns()
        if kind in ("reviewQuestion", "previewQuestion"):
            if on("inline") or on("click_choice"):
                halves = P.split_answer_side(card.answer())
                if halves:
                    return P.inline_reveal(text, _prep(halves[1]), pat, **opts)
            halves = P.split_answer_side(card.answer())
            return P.mark_questions(text, pat, _prep(halves[1]) if halves else "")
        if kind in ("reviewAnswer", "previewAnswer"):
            # หน้าเฉลย: ส่วนโจทย์ยังคลิกตอบได้ และจำข้อที่ตอบไว้ตั้งแต่หน้าโจทย์
            m = P._ANSWER_HR.search(text)
            if m:
                head, tail = text[:m.start()], text[m.end():]
                if on("click_choice"):
                    head = P.inline_reveal(head, tail, pat, **opts)
                if "mqa-q" not in head:
                    head = P.mark_questions(head, pat, tail)
                return head + text[m.start():]
    except Exception as e:  # ไม่ให้การ์ดพังเพราะ add-on
        print("[multi-question]", e)
    return text


def _wrap_key(idx, orig):
    def handler():
        rev = mw.reviewer
        if _mv["open"]:
            rev.web.eval(f"mqaMvKey({idx})")
            return
        if on("inline") and getattr(rev, "state", None) == "question":
            def done(handled):
                if not handled and orig:
                    orig()
            rev.web.evalWithCallback(f"mqaToggle({idx})", done)
        elif orig:
            orig()
    return handler


def on_shortcuts(state, shortcuts):
    if state != "review":
        return
    for idx in range(1, 11):
        key = str(idx % 10)
        pos = next((i for i, s in enumerate(shortcuts) if s[0] == key), None)
        orig = shortcuts[pos][1] if pos is not None else None
        entry = (key, _wrap_key(idx, orig))
        keymap.register(["review"], key, entry[1])      # แป้นไทย: ปุ่มตัวเลขตำแหน่งเดิมก็ใช้ได้
        if pos is None:
            shortcuts.append(entry)
        else:
            shortcuts[pos] = entry

    def panel_or(orig, js):
        def handler():
            if _mv["open"]:
                if js:
                    mw.reviewer.web.eval(js)
            elif orig:
                orig()
        return handler

    for key, js in ((Qt.Key.Key_Return, "mqaMoveConfirm()"), (Qt.Key.Key_Enter, "mqaMoveConfirm()"),
                    (" ", ""), ("Escape", "mqaMoveClose()")):
        pos = next((i for i, sc in enumerate(shortcuts) if sc[0] == key), None)
        orig = shortcuts[pos][1] if pos is not None else None
        entry = (key, panel_or(orig, js))
        if pos is None:
            shortcuts.append(entry)
        else:
            shortcuts[pos] = entry


def redraw_reviewer():
    if mw.state != "review" or not getattr(mw.reviewer, "card", None):
        return
    try:
        mw.reviewer._redraw_current_card()
    except Exception:
        try:
            if mw.reviewer.state == "answer":
                mw.reviewer._showAnswer()
            else:
                mw.reviewer._showQuestion()
        except Exception:
            pass


# ---------------------------------------------------------------- split tool

def _split_pieces(note):
    if len(note.fields) < 2:
        return []
    return P.split_fields(note.fields[0], note.fields[1], patterns())


def _run_op(parent, name, body, on_done):
    from aqt.operations import CollectionOp

    def op(col):
        pos = col.add_custom_undo_entry(name)
        body(col)
        return col.merge_undo_entries(pos)

    CollectionOp(parent, op).success(lambda _: on_done()).run_in_background()


def split_in_add(addcards: AddCards):
    editor = addcards.editor

    def go():
        note = editor.note
        pieces = _split_pieces(note) if note else []
        if len(pieces) < 2:
            showInfo("ไม่พบโจทย์หลายข้อในการ์ดนี้\n"
                     "โจทย์ต้องขึ้นต้นด้วย \"ข้อ 1\" ในช่องแรก "
                     "และเฉลยขึ้นต้นด้วย \"ข้อ 1 ตอบ\" ในช่องที่สอง", parent=addcards)
            return
        missing = [str(n) for n, _f, b in pieces if not b]
        msg = f"จะเพิ่มเป็น {len(pieces)} การ์ด (ข้อละใบ)"
        if missing:
            msg += f"\n\nข้อที่ไม่พบเฉลย: {', '.join(missing)}"
        if not askUser(msg + "\n\nดำเนินการต่อไหม?", parent=addcards):
            return
        did = addcards.deck_chooser.selected_deck_id
        nt = note.note_type()
        tags = list(note.tags)

        def body(col):
            for _n, front, back in pieces:
                new = col.new_note(nt)
                new.fields[0], new.fields[1] = front, back
                new.tags = tags + [TAG_NEW]
                col.add_note(new, did)

        def done():
            for i in range(len(note.fields)):
                note.fields[i] = ""
            editor.loadNote()
            tooltip(f"เพิ่มแล้ว {len(pieces)} การ์ด")

        _run_op(addcards, "แยกการ์ดเป็นข้อละใบ", body, done)

    editor.call_after_note_saved(go)


def split_in_browser(browser):
    nids = list(browser.selected_notes())
    if not nids:
        tooltip("เลือกโน้ตก่อน")
        return
    col = mw.col
    plan = []
    for nid in nids:
        note = col.get_note(nid)
        pieces = _split_pieces(note)
        if len(pieces) >= 2:
            plan.append((nid, pieces))
    if not plan:
        showInfo("โน้ตที่เลือกไม่มีโจทย์หลายข้อที่แยกได้", parent=browser)
        return
    total = sum(len(p) for _, p in plan)
    if not askUser(
        f"จะแยก {len(plan)} โน้ต เป็น {total} การ์ดใหม่ (ข้อละใบ)\n\n"
        f"โน้ตต้นฉบับจะถูกพักการ์ด (suspend) และติดแท็ก {TAG_ORIG}\n"
        "กด Undo (Ctrl+Z) เพื่อย้อนกลับได้\n\nดำเนินการต่อไหม?",
        parent=browser,
    ):
        return

    def body(col):
        for nid, pieces in plan:
            orig = col.get_note(nid)
            cids = orig.card_ids()
            first = col.get_card(cids[0])
            did = first.odid or first.did
            for _n, front, back in pieces:
                new = col.new_note(orig.note_type())
                new.fields[0], new.fields[1] = front, back
                new.tags = [t for t in orig.tags if t != TAG_ORIG] + [TAG_NEW]
                col.add_note(new, did)
            orig.add_tag(TAG_ORIG)
            col.update_note(orig)
            col.sched.suspend_cards(cids)

    _run_op(browser, "แยกการ์ดเป็นข้อละใบ", body,
            lambda: tooltip(f"สร้างแล้ว {total} การ์ด"))


def on_browser_menus(browser):
    if not on("split_tool"):
        return
    act = QAction("แยกเป็นข้อละใบ (การ์ดหลายข้อ)", browser)
    act.triggered.connect(lambda: split_in_browser(browser))
    browser.form.menu_Notes.addSeparator()
    browser.form.menu_Notes.addAction(act)


def on_browser_context(browser, menu):
    if on("split_tool"):
        menu.addSeparator()
        menu.addAction("แยกเป็นข้อละใบ (การ์ดหลายข้อ)",
                       lambda: split_in_browser(browser))


def on_editor_buttons(buttons, editor):
    win = getattr(editor, "parentWindow", None)
    if isinstance(win, AddCards) and on("split_tool"):
        buttons.append(editor.addButton(
            icon=None, cmd="mqa_split", func=lambda e: split_in_add(win),
            tip="แยกโจทย์หลายข้อเป็นการ์ดข้อละใบ แล้วเพิ่มทันที",
            label="✂ แยกข้อ", disables=False,
        ))
    return buttons


# ---------------------------------------------------------------- move questions (panel)

MOVE_KEY = "Ctrl+Shift+M"
TAG_MOVED = "ย้ายจากการ์ดรวม"

MOVE_CSS_JS = r"""
<style>
  #mqa-mv { position: fixed; top: 0; right: 0; bottom: 0; width: min(440px, 42vw); z-index: 9999;
    display: flex; flex-direction: column; background: #fff; color: #1f2328; text-align: left;
    box-shadow: -10px 0 28px rgba(0,0,0,.28); transform: translateX(105%);
    transition: transform .22s ease; font: 14px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
  .nightMode #mqa-mv, .night_mode #mqa-mv { background: #262626; color: #e8e8e8; }
  body.mqa-mv-open #mqa-mv { transform: none; }
  #qa { transition: margin-right .22s ease; }
  body.mqa-mv-open #qa { margin-right: min(440px, 42vw); }
  .mqa-mv-head { display: flex; align-items: center; padding: 12px 14px; font-weight: 600;
    border-bottom: 1px solid rgba(128,128,128,.25); }
  .mqa-mv-head span { flex: 1; }
  .mqa-mv-x { cursor: pointer; opacity: .6; padding: 0 8px; border-radius: 6px; font-size: 18px; }
  .mqa-mv-x:hover { opacity: 1; background: rgba(128,128,128,.15); }
  .mqa-mv-list { overflow: auto; max-height: 40%; padding: 6px; flex: none; }
  .mqa-mv-item { display: flex; align-items: center; gap: 10px; padding: 6px 10px; border-radius: 8px;
    cursor: pointer; user-select: none; }
  .mqa-mv-item:hover { background: rgba(128,128,128,.12); }
  .mqa-mv-item.cur { background: rgba(59,130,246,.18); }
  .mqa-mv-item input { width: 16px; height: 16px; margin: 0; cursor: pointer; accent-color: #3b82f6; }
  .mqa-mv-k { font: 600 11px ui-monospace, Consolas, monospace; opacity: .45; width: 12px; text-align: center; }
  .mqa-mv-st { margin-left: auto; font-size: 12px; opacity: .7; }
  .mqa-mv-st.bad { color: #ef4444; opacity: 1; }
  .mqa-mv-st.good { color: #22c55e; opacity: 1; }
  .mqa-mv-prev { flex: 1; overflow: auto; padding: 10px 14px 14px; border-top: 1px solid rgba(128,128,128,.25); }
  .mqa-mv-lbl { font-size: 12px; opacity: .6; margin: 4px 0 6px; }
  .mqa-mv-box { border-left: 3px solid #22c55e; background: rgba(34,197,94,.09); border-radius: 6px;
    padding: 10px 12px; text-align: center; }
  .mqa-mv-foot { padding: 10px 14px 12px; border-top: 1px solid rgba(128,128,128,.25);
    display: flex; flex-direction: column; gap: 8px; }
  .mqa-mv-deck { font-size: 13px; }
  .mqa-mv-deck a { color: #3b82f6; cursor: pointer; margin-left: 6px; text-decoration: none; }
  .mqa-mv-row { display: flex; align-items: center; gap: 8px; }
  .mqa-mv-sum { flex: 1; font-size: 12px; opacity: .7; }
  .mqa-mv-btn { padding: 6px 14px; border-radius: 8px; border: 1px solid rgba(128,128,128,.4);
    background: transparent; color: inherit; cursor: pointer; font: inherit; }
  .mqa-mv-btn.go { background: #3b82f6; border-color: #3b82f6; color: #fff; }
  .mqa-mv-btn:disabled { opacity: .4; cursor: default; }
  .mqa-mv-hint { font-size: 11px; opacity: .5; }
  #mqa-hl { position: absolute; pointer-events: none; z-index: 5; border-radius: 10px;
    box-shadow: 0 0 0 2px rgba(59,130,246,.9); background: rgba(59,130,246,.07);
    transition: top .2s ease, height .2s ease, left .2s ease, width .2s ease; }
</style>
<script>
  var mqaMv = null;
  function mqaEsc(t) { var d = document.createElement("div"); d.textContent = t; return d.innerHTML; }
  function mqaMvStatus(n) {
    var s = document.querySelector('.mqa-slot[data-n="' + n + '"]');
    if (!s) return ["—", "", false];
    var p = s.getAttribute("data-picked"), a = s.getAttribute("data-ans");
    if (p && a && p !== a) return ["✗ ตอบ " + p + ".", "bad", true];
    if (p && p === a) return ["✓", "good", false];
    return ["—", "", false];
  }
  function mqaMoveOpen(d) {
    mqaMv = { items: d.items, deck: d.deck, cur: 0, checked: {} };
    d.items.forEach(function (it) { if (mqaMvStatus(it.n)[2]) mqaMv.checked[it.n] = true; });
    var first = d.items.findIndex(function (it) { return mqaMv.checked[it.n]; });
    mqaMv.cur = first < 0 ? 0 : first;
    var el = document.getElementById("mqa-mv");
    if (!el) { el = document.createElement("div"); el.id = "mqa-mv"; document.body.appendChild(el); }
    mqaMvRender();
    requestAnimationFrame(function () { document.body.classList.add("mqa-mv-open"); });
    setTimeout(mqaMvFocus, 260);
    pycmd("mqamove:state:1");
  }
  function mqaMvRender() {
    if (!mqaMv) return;
    var it = mqaMv.items, n = 0, h = [];
    h.push('<div class="mqa-mv-head"><span>↪ ย้ายข้อไปการ์ดใหม่</span>' +
           '<div class="mqa-mv-x" title="ปิด (Esc)" onclick="mqaMoveClose()">×</div></div>');
    h.push('<div class="mqa-mv-list">');
    it.forEach(function (x, k) {
      var st = mqaMvStatus(x.n), on = !!mqaMv.checked[x.n];
      if (on) n++;
      h.push('<div class="mqa-mv-item' + (k === mqaMv.cur ? " cur" : "") + '" onclick="mqaMvSel(' + k + ')">' +
        '<span class="mqa-mv-k">' + (k < 10 ? (k + 1) % 10 : "") + '</span>' +
        '<input type="checkbox"' + (on ? " checked" : "") + ' onclick="mqaMvTick(' + k + ',event)">' +
        '<span>ข้อ ' + x.n + '</span><span class="mqa-mv-st ' + st[1] + '">' + st[0] + '</span></div>');
    });
    h.push('</div>');
    var c = it[mqaMv.cur];
    h.push('<div class="mqa-mv-prev"><div class="mqa-mv-lbl">ด้านหน้า: ข้อที่ไฮไลต์บนการ์ด</div>' +
      '<div class="mqa-mv-lbl">ด้านหลัง (เฉลยข้อ ' + c.n + ')</div>' +
      '<div class="mqa-mv-box">' + (c.back || '<i style="opacity:.6">ไม่พบเฉลยของข้อนี้</i>') + '</div></div>');
    h.push('<div class="mqa-mv-foot"><div class="mqa-mv-deck">ไปที่ deck: <b>' + mqaEsc(mqaMv.deck) +
      '</b><a onclick="pycmd(\'mqamove:deck\')">เปลี่ยน</a></div>' +
      '<div class="mqa-mv-row"><span class="mqa-mv-sum">การ์ดเดิมจะเหลือ ' + (it.length - n) + ' จาก ' + it.length + ' ข้อ</span>' +
      '<button class="mqa-mv-btn" onclick="mqaMoveClose()">ยกเลิก</button>' +
      '<button class="mqa-mv-btn go" onclick="mqaMoveConfirm()"' + (n ? "" : " disabled") + '>ย้าย ' + n + ' ข้อ</button></div>' +
      '<div class="mqa-mv-hint">กดเลขเพื่อติ๊ก · Enter ย้าย · Esc ปิด</div></div>');
    var el = document.getElementById("mqa-mv");
    var ls = el.querySelector(".mqa-mv-list"), keep = ls ? ls.scrollTop : 0;
    el.innerHTML = h.join("");
    el.querySelector(".mqa-mv-list").scrollTop = keep;
    var box = el.querySelector(".mqa-mv-box");
    if (window.MathJax && MathJax.typesetPromise) MathJax.typesetPromise([box]).catch(function () {});
  }
  function mqaMvSel(k) { mqaMv.cur = k; mqaMvRender(); mqaMvFocus(); }
  function mqaMvTick(k, ev) {
    if (ev) ev.stopPropagation();
    var n = mqaMv.items[k].n;
    if (mqaMv.checked[n]) delete mqaMv.checked[n]; else mqaMv.checked[n] = true;
    mqaMv.cur = k; mqaMvRender(); mqaMvFocus();
  }
  function mqaMvKey(i) { if (mqaMv && i - 1 < mqaMv.items.length) mqaMvTick(i - 1); }
  function mqaMvFocus() {
    if (!mqaMv) return;
    var n = mqaMv.items[mqaMv.cur].n, qa = document.getElementById("qa") || document.body;
    var marks = Array.prototype.slice.call(document.querySelectorAll(".mqa-q[data-n]"));
    var k = marks.findIndex(function (m) { return m.getAttribute("data-n") == n; });
    if (k < 0) return;
    var r = document.createRange();
    r.setStartBefore(marks[k]);
    var stop = marks[k + 1] || document.getElementById("answer");
    if (stop) r.setEndBefore(stop); else r.setEndAfter(qa.lastChild);
    var b = r.getBoundingClientRect();
    if (!b.height) return;
    var hl = document.getElementById("mqa-hl");
    if (!hl) { hl = document.createElement("div"); hl.id = "mqa-hl"; document.body.appendChild(hl); }
    hl.style.top = (b.top + window.scrollY - 8) + "px";
    hl.style.left = (b.left + window.scrollX - 12) + "px";
    hl.style.width = (b.width + 24) + "px";
    hl.style.height = (b.height + 16) + "px";
    window.scrollTo({ top: b.top + window.scrollY - 90, behavior: "smooth" });
  }
  function mqaMoveConfirm() {
    if (!mqaMv) return;
    var nums = Object.keys(mqaMv.checked);
    if (nums.length) pycmd("mqamove:do:" + JSON.stringify(nums));
  }
  function mqaMoveSetDeck(name) { if (mqaMv) { mqaMv.deck = name; mqaMvRender(); } }
  function mqaMoveClose() {
    document.body.classList.remove("mqa-mv-open");
    var hl = document.getElementById("mqa-hl");
    if (hl) hl.remove();
    mqaMv = null;
    pycmd("mqamove:state:0");
  }
</script>
"""

_mv = {"open": False, "nid": None, "cid": None, "did": None}


def _rev_eval(js):
    try:
        mw.reviewer.web.eval(js)
    except Exception:
        pass


def open_move_panel():
    import json
    rev = mw.reviewer
    card = getattr(rev, "card", None) if mw.state == "review" else None
    if card is None:
        tooltip("ใช้ได้ระหว่างทบทวนการ์ด")
        return
    if _mv["open"]:
        _rev_eval("mqaMoveClose()")
        return
    note = card.note()
    pieces = P.split_fields(note.fields[0], note.fields[1], patterns()) if len(note.fields) >= 2 else []
    if not pieces:
        showInfo("การ์ดนี้ไม่มีโจทย์หลายข้อที่ย้ายได้\n"
                 "โจทย์ต้องขึ้นต้นด้วย \"ข้อ 1\" ในช่องแรก และเฉลย \"ข้อ 1 ตอบ\" ในช่องที่สอง")
        return
    did = card.odid or card.did
    _mv.update(nid=note.id, cid=card.id, did=did)
    data = {"deck": mw.col.decks.name(did),
            "items": [{"n": n, "back": _prep(back)} for n, _f, back in pieces]}
    _rev_eval(f"mqaMoveOpen({json.dumps(data)})")


def _choose_deck():
    from aqt.studydeck import StudyDeck

    def picked(sd):
        name = getattr(sd, "name", None)
        if not name:
            return
        try:
            _mv["did"] = mw.col.decks.id_for_name(name)
        except Exception:
            _mv["did"] = mw.col.decks.id(name, create=False)
        import json
        _rev_eval(f"mqaMoveSetDeck({json.dumps(name)})")

    StudyDeck(mw, current=mw.col.decks.name(_mv["did"]), accept="เลือก",
              title="ย้ายไปที่ deck", parent=mw, dyn=False, callback=picked)


def _do_move(nums):
    nums = set(nums)
    note = mw.col.get_note(_mv["nid"])
    pat = patterns()
    pieces = P.split_fields(note.fields[0], note.fields[1], pat)
    chosen = [p for p in pieces if p[0] in nums]
    if not chosen:
        return
    if len(chosen) == len(pieces) and not askUser(
            "จะย้ายทุกข้อ การ์ดเดิมจะไม่เหลือโจทย์เลย\n\nดำเนินการต่อไหม?", parent=mw):
        return
    did, nid, cid = _mv["did"], note.id, _mv["cid"]
    tags = [t for t in note.tags if t not in (TAG_ORIG, TAG_NEW)]

    def body(col):
        orig = col.get_note(nid)
        nt = orig.note_type()
        for _n, front, back in chosen:
            new = col.new_note(nt)
            new.fields[0], new.fields[1] = front, back
            new.tags = tags + [TAG_MOVED]
            col.add_note(new, did)
        orig.fields[0], orig.fields[1] = P.remove_questions(orig.fields[0], orig.fields[1], nums, pat)
        col.update_note(orig)

    def done():
        _rev_eval(f"mqaMoveClose(); if (window.mqaState) delete mqaState['{cid}'];")
        tooltip(f"ย้ายแล้ว {len(chosen)} ข้อ (Ctrl+Z เพื่อย้อนกลับ)")
        redraw_reviewer()

    _run_op(mw, "ย้ายข้อไปการ์ดใหม่", body, done)


def on_js_message(handled, message, context):
    if not isinstance(message, str) or not message.startswith("mqamove:"):
        return handled
    import json
    cmd = message[len("mqamove:"):]
    if cmd.startswith("state:"):
        _mv["open"] = cmd.endswith("1")
    elif cmd == "deck":
        QTimer.singleShot(0, _choose_deck)
    elif cmd.startswith("do:"):
        nums = json.loads(cmd[3:])
        QTimer.singleShot(0, lambda: _do_move(nums))
    return (True, None)


def on_show_question(_card):
    _mv["open"] = False   # หน้าใหม่ แผงถูกล้างไปพร้อมการ์ด


# ---------------------------------------------------------------- settings UI

# (key, ชื่อ, คำอธิบาย, ตัวเลือกแม่) — ตัวเลือกย่อยใช้ได้เมื่อเปิดตัวแม่
OPTIONS = [
    ("inline", "เฉลยใต้แต่ละข้อ",
     "หน้าโจทย์: มีปุ่ม \"▸ เฉลยข้อ …\" ใต้โจทย์แต่ละข้อ "
     "กดปุ่ม หรือกดเลข 1–9 และ 0 (ข้อที่ 10) เพื่อเปิด/ปิด", None),
    ("inline_stealth", "ไม่แสดงปุ่มจาง ๆ ตอนเปิดการ์ด",
     "ปุ่มล่องหนตั้งแต่แรก โผล่เฉพาะตอนชี้เมาส์ที่ข้อนั้น (กดเลขยังใช้ได้)", "inline"),
    ("inline_hidebtn", "ไม่แสดงปุ่มเลย",
     "ไม่มีปุ่มเฉลยบนการ์ด เปิดเฉลยด้วยการคลิกตัวเลือก หรือกดเลข 1–9, 0", "inline"),
    ("inline_accordion", "เปิดเฉลยใหม่แล้วปิดเฉลยเก่าอัตโนมัติ",
     "เปิดได้ทีละข้อ ข้อเก่ากลับเป็นโจทย์สะอาด (ซ่อนเฉลยและกรอบถูก/ผิด) ข้อที่กำลังทำไม่ขยับ", "inline"),
    ("click_choice", "คลิกตัวเลือกเพื่อตอบ",
     "หน้าโจทย์: คลิก ก./ข./ค./ง. ที่คิดว่าถูก → เปิดเฉลยข้อนั้น "
     "ตัวเลือกที่ถูกเป็นสีเขียว ถ้าตอบผิดตัวที่เลือกเป็นสีแดง · ตอบแล้วคลิกซ้ำเพื่อซ่อน/แสดงผลของข้อนั้น", None),
    ("choice_noframe", "ไม่มีกรอบสี เหลือแค่ ✅ ❌",
     "ตัวเลือกที่ตอบไม่มีกล่องสีเขียว/แดง มีแค่อีโมจิต่อท้าย", "click_choice"),
    ("compact_answer", "ย่อเฉลยเหลือแค่บรรทัดคำตอบ",
     "ถ้าเปิดคลิกตอบ: เหลือแค่ 💡 ใต้ข้อ กดเพื่ออ่านเฉลยเต็ม · ถ้าไม่ได้เปิด: เหลือบรรทัด \"✅ ข้อ … ตอบ …\" "
     "คำอธิบายพับไว้ใต้ \"▸ อ่านคำอธิบาย\"", None),
    ("split_tool", "เครื่องมือแยกเป็นข้อละใบ",
     "เพิ่มปุ่ม \"✂ แยกข้อ\" ในหน้า Add และเมนู Notes ใน Browse "
     "(มีผลเมื่อเปิดหน้าต่างนั้นใหม่)", None),
    ("hover_open", "ชี้เมาส์ที่เมนูแล้วเปิดเลย",
     "เมนู 🃏 CardKit และ 📚 Rounds บนแถบเมนู เปิดเองเมื่อชี้เมาส์ค้างไว้ครู่หนึ่ง ไม่ต้องคลิก", None),
    ("keep_scroll", "แก้การ์ด (Edit) เสร็จแล้วอยู่ตำแหน่งเดิม",
     "ระหว่างท่อง กด Edit แก้การ์ดแล้วปิด หน้าการ์ดจะเลื่อนกลับไปตำแหน่งเดิม ไม่เด้งขึ้นบนสุด", None),
    ("edit_here", "เปิด Edit แล้วไปตรงที่อ่านอยู่",
     "ระหว่างท่อง กด Edit หน้าต่างแก้ไขจะเลื่อนไปตรงข้อความที่อ่านค้างอยู่ ไม่เริ่มบนสุด", None),
    ("mouse_back", "ปุ่มเมาส์ข้าง (Back) = ย้อนกลับ",
     "ปิดเมนู/หน้าต่างที่เปิดอยู่ (เช่น Edit กลับไปการ์ดเดิม) · ถ้าอยู่หน้าหลัก = กลับหน้า Decks เหมือนกด D", None),
    ("mouse_mid_quote", "คลิกปุ่มเมาส์กลาง = พิมพ์ \"",
     "ตอนกำลังพิมพ์ (เช่นช่องแก้การ์ด) คลิกปุ่มกลาง 1 ครั้ง ใส่ \" ตรงเคอร์เซอร์", None),
]


class SettingsDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent or mw)
        self.setWindowTitle("CardKit · ตั้งค่าการ์ดหลายข้อ")
        self.setMinimumWidth(460)
        lay = QVBoxLayout(self)
        self.boxes = {}
        c = cfg()
        for key, title, desc, parent_key in OPTIONS:
            box = QCheckBox(title)
            box.setChecked(bool(c.get(KEYS[key], key in DEFAULT_ON)))
            f = box.font()
            f.setBold(parent_key is None)
            box.setFont(f)
            note = QLabel(desc)
            note.setWordWrap(True)
            indent = 28 if parent_key else 0
            box.setContentsMargins(indent, 0, 0, 0)
            box.setStyleSheet(f"margin-left: {indent}px;")
            note.setContentsMargins(24 + indent, 0, 0, 10)
            note.setStyleSheet("color: gray;")
            lay.addWidget(box)
            lay.addWidget(note)
            self.boxes[key] = box
            if parent_key:
                parent = self.boxes[parent_key]
                box.setEnabled(parent.isChecked())
                note.setEnabled(parent.isChecked())
                parent.toggled.connect(box.setEnabled)
                parent.toggled.connect(note.setEnabled)
        # "ไม่แสดงจาง ๆ ตอนเปิด" ไม่มีผลถ้าเลือก "ไม่แสดงปุ่มเลย"
        st, hb = self.boxes["inline_stealth"], self.boxes["inline_hidebtn"]

        def _refresh_stealth(*_):
            st.setEnabled(self.boxes["inline"].isChecked() and not hb.isChecked())

        hb.toggled.connect(_refresh_stealth)
        self.boxes["inline"].toggled.connect(_refresh_stealth)
        _refresh_stealth()
        hint = QLabel(
            "รูปแบบที่รองรับ: โจทย์ขึ้นต้น \"ข้อ 82 …\" ในช่องแรก "
            "และเฉลยขึ้นต้น \"✅ ข้อ 82 ตอบ …\" ในช่องที่สอง "
            "(ปรับรูปแบบได้ใน Tools → Add-ons → Config)"
        )
        hint.setWordWrap(True)
        hint.setStyleSheet("color: gray; font-size: 11px;")
        lay.addWidget(hint)
        bb = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        lay.addWidget(bb)

    def accept(self):
        c = cfg()
        for key, box in self.boxes.items():
            c[KEYS[key]] = box.isChecked()
        mw.addonManager.writeConfig(ADDON, c)
        super().accept()
        redraw_reviewer()
        tooltip("บันทึกการตั้งค่าแล้ว")


def open_settings():
    SettingsDialog(mw).exec()


MENU_TITLE = "🃏 CardKit"
_menu = None
_move_act = None


def _toggle(feature, checked):
    c = cfg()
    c[KEYS[feature]] = bool(checked)
    mw.addonManager.writeConfig(ADDON, c)
    redraw_reviewer()
    tooltip(("เปิด" if checked else "ปิด") + ": " +
            next(o[1] for o in OPTIONS if o[0] == feature))


def _sync_checks():
    for act in _menu.actions():
        feature = act.data()
        if feature in KEYS:
            act.setChecked(on(feature))
            parent_key = next(o[3] for o in OPTIONS if o[0] == feature)
            if parent_key:
                ok = on(parent_key)
                if feature == "inline_stealth":
                    ok = ok and not on("inline_hidebtn")
                act.setEnabled(ok)


# ---------------------------------------------------------------- hover to open

HOVER_MENUS = []          # เมนูที่ชี้แล้วเปิดเอง (CardKit, Rounds)


class _HoverOpen(QObject):
    """ชี้เมาส์ที่ชื่อเมนูบนแถบเมนูค้างครู่หนึ่ง → เปิดเมนูเอง
    การคลิกสำคัญกว่า: คลิกระหว่างรอ = เปิดตามปกติ · คลิกทันทีหลังเมนูเพิ่งเด้งเอง = ไม่นับเป็นการปิด"""

    DELAY = 250          # ชี้ค้างกี่ ms ถึงเปิด
    GRACE = 600          # หลังเปิดเอง คลิกที่ชื่อเมนูภายในกี่ ms จะไม่ปิดเมนู

    def __init__(self, bar):
        super().__init__(bar)
        self.bar = bar
        self.pending = None
        self.opened = None          # (action, เวลาที่เปิดเอง)
        self.timer = QTimer(self)
        self.timer.setSingleShot(True)
        self.timer.timeout.connect(self._fire)
        bar.setMouseTracking(True)

    def _ours(self):
        return [m.menuAction() for m in HOVER_MENUS if m is not None]

    def watch(self, menu):
        menu.installEventFilter(self)

    @staticmethod
    def _gpos(ev):
        try:
            return ev.globalPosition().toPoint()
        except AttributeError:
            return ev.globalPos()

    def _just_opened_here(self, gpos):
        if not self.opened:
            return False
        act, t = self.opened
        if time.monotonic() - t > self.GRACE / 1000:
            return False
        return self.bar.actionAt(self.bar.mapFromGlobal(gpos)) is act

    def eventFilter(self, obj, ev):
        t = ev.type()
        if t in (QEvent.Type.MouseButtonPress, QEvent.Type.MouseButtonRelease,
                 QEvent.Type.MouseButtonDblClick):
            if obj is self.bar:
                self.timer.stop()          # คลิกเอง = ยกเลิกการเปิดอัตโนมัติ ให้ Qt จัดการตามปกติ
                self.pending = None
            if self._just_opened_here(self._gpos(ev)):
                return True                # เพิ่งเด้งเองตรงที่คลิก → ไม่นับเป็นการปิด
            return False
        if obj is not self.bar:
            return False
        if t == QEvent.Type.MouseMove:
            try:
                pos = ev.position().toPoint()
            except AttributeError:
                pos = ev.pos()
            act = self.bar.actionAt(pos)
            if act is not None and act in self._ours():
                if act is not self.pending:
                    self.pending = act
                    self.timer.start(self.DELAY)
            else:
                self.pending = None
                self.timer.stop()
        elif t == QEvent.Type.Leave:
            self.pending = None
            self.timer.stop()
        return False

    def _fire(self):
        act, self.pending = self.pending, None
        if act is None or not on("hover_open") or QApplication.activePopupWidget() is not None:
            return
        if not mw.isActiveWindow():
            return
        if QApplication.mouseButtons() != Qt.MouseButton.NoButton:
            return                         # กำลังกดเมาส์อยู่ ปล่อยให้การคลิกทำงาน
        if self.bar.actionAt(self.bar.mapFromGlobal(QCursor.pos())) is act:
            self.opened = (act, time.monotonic())
            self.bar.setActiveAction(act)


_hover = None


def setup_hover():
    global _hover
    if _hover is None:
        bar = mw.form.menubar
        _hover = _HoverOpen(bar)
        bar.installEventFilter(_hover)
    for m in HOVER_MENUS:
        _hover.watch(m)


def setup_menubar():
    """เมนู "🃏 CardKit" ถัดจาก Help กดแล้วเปิด/ปิดแต่ละฟีเจอร์ได้ทันที"""
    global _menu
    bar = mw.form.menubar
    _menu = QMenu(MENU_TITLE, mw)
    for key, title, desc, parent_key in OPTIONS:
        act = _menu.addAction(("      ↳ " + title) if parent_key else title)
        act.setCheckable(True)
        act.setData(key)
        act.setToolTip(desc)
        act.triggered.connect(lambda checked, k=key: _toggle(k, checked))
    _menu.addSeparator()
    _menu.addAction("ตั้งค่าเพิ่มเติม…", open_settings)
    global _move_act
    _move_act = QAction("↪ ย้ายข้อจากการ์ดนี้…", mw)
    _move_act.setShortcut(QKeySequence(MOVE_KEY))
    _move_act.triggered.connect(open_move_panel)
    first = _menu.actions()[0]
    _menu.insertAction(first, _move_act)
    _menu.insertSeparator(first)
    _menu.setToolTipsVisible(True)
    _menu.aboutToShow.connect(_sync_checks)
    _sync_checks()

    # วางถัดจาก Help (ก่อนเมนูของ add-on อื่น)
    acts = bar.actions()
    help_act = mw.form.menuHelp.menuAction()
    after = acts[acts.index(help_act) + 1] if help_act in acts and acts.index(help_act) + 1 < len(acts) else None
    if after is not None:
        bar.insertMenu(after, _menu)
    else:
        bar.addMenu(_menu)
    _menu.menuAction().setToolTip("ตั้งค่าการ์ดหลายข้อ")
    HOVER_MENUS.append(_menu)
    setup_hover()


# ---------------------------------------------------------------- hooks

gui_hooks.webview_will_set_content.append(on_set_content)
gui_hooks.card_will_show.append(on_card_will_show)
gui_hooks.state_shortcuts_will_change.append(on_shortcuts)
gui_hooks.browser_menus_did_init.append(on_browser_menus)
gui_hooks.browser_will_show_context_menu.append(on_browser_context)
gui_hooks.editor_did_init_buttons.append(on_editor_buttons)
gui_hooks.main_window_did_init.append(setup_menubar)
gui_hooks.webview_did_receive_js_message.append(on_js_message)
gui_hooks.reviewer_did_show_question.append(on_show_question)


# ---------------------------------------------------------------- Edit เสร็จแล้วอยู่ตำแหน่งเดิม

_scroll = {"cid": None, "y": 0, "until": 0.0}


def _on_editor_init(editor):
    """เปิดหน้าต่าง Edit ระหว่างท่อง → จำตำแหน่งเลื่อนของการ์ดไว้"""
    if mw.state != "review" or type(editor.parentWindow).__name__ != "EditCurrent":
        return
    card = mw.reviewer.card
    if card is None:
        return
    if on("edit_here"):
        _edit_jump_setup(editor)
    if not on("keep_scroll"):
        return
    cid = card.id

    def got(y):
        try:
            _scroll.update(cid=cid, y=int(y or 0), until=float("inf"))   # ระหว่างเปิด Edit: คืนตำแหน่งทุกครั้งที่วาดใหม่
        except Exception:
            pass

    mw.reviewer.web.evalWithCallback("window.scrollY", got)

    def closed(*_a):
        _scroll["until"] = time.time() + 2.5         # ปิด Edit แล้ว: การ์ดถูกวาดใหม่ภายในช่วงนี้

    win = editor.parentWindow
    for sig in ("finished", "destroyed"):
        try:
            getattr(win, sig).connect(closed)
        except Exception:
            pass


def _restore_scroll(card):
    if not on("keep_scroll") or _scroll["cid"] is None or card is None or card.id != _scroll["cid"]:
        return
    if time.time() > _scroll["until"]:
        _scroll["cid"] = None
        return
    y = _scroll["y"]
    # เลื่อนซ้ำหลายจังหวะ เผื่อรูป/MathJax โหลดช้าแล้วความสูงหน้าเปลี่ยน
    mw.reviewer.web.eval(
        f"[0,80,300,800].forEach(function(t){{setTimeout(function(){{window.scrollTo(0,{y});}},t);}});")


# ---------------------------------------------------------------- เปิด Edit แล้วไปตรงที่อ่านอยู่

# ในหน้าทบทวน: เก็บข้อความที่อยู่บนสุดของจอ (หลายจุด เผื่อบางอันหาไม่เจอ) + สัดส่วนที่เลื่อนไป
_ANCHOR_JS = r"""
(function(){
  var out = [], seen = {};
  var x = Math.round(window.innerWidth / 2);
  [12, 40, 80, 140, 220, 320].forEach(function(y){
    [x, 60, Math.round(window.innerWidth / 4)].forEach(function(px){
      var r = document.caretRangeFromPoint && document.caretRangeFromPoint(px, y);
      var n = r && r.startContainer;
      if (!n || n.nodeType !== 3) return;
      var t = (n.data || '').replace(/\s+/g, ' ').trim();
      if (t.length < 6) return;
      t = t.slice(0, 40);
      if (!seen[t]) { seen[t] = 1; out.push(t); }
    });
  });
  var h = document.documentElement.scrollHeight - window.innerHeight;
  return JSON.stringify({a: out, r: h > 0 ? window.scrollY / h : 0, y: window.scrollY});
})()
"""

# ในหน้าต่าง Edit: หาข้อความเดียวกัน (ทะลุ shadow DOM ของช่อง) แล้วเลื่อนไปไว้บนสุด
_JUMP_JS = r"""
(function(d){
  if (!d || d.y < 30) return;
  var userMoved = false;
  ['wheel','keydown','mousedown','touchstart'].forEach(function(ev){
    window.addEventListener(ev, function(){ userMoved = true; }, {capture:true, once:true});
  });
  function norm(t){ return (t || '').replace(/\s+/g, ' '); }
  function texts(root, acc){
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    var n; while ((n = w.nextNode())) acc.push(n);
    root.querySelectorAll('*').forEach(function(el){ if (el.shadowRoot) texts(el.shadowRoot, acc); });
    return acc;
  }
  function visible(el){ return el && el.getClientRects().length > 0 && !el.closest('.CodeMirror'); }
  function find(){
    var nodes = texts(document, []);
    for (var i = 0; i < d.a.length; i++){
      var want = d.a[i], hit = [];
      for (var k = 0; k < nodes.length; k++){
        var t = norm(nodes[k].data), at = t.indexOf(want);
        if (at >= 0 && visible(nodes[k].parentElement)) hit.push([nodes[k], at]);
      }
      if (hit.length === 1) return hit[0];          // ข้อความซ้ำหลายที่ = ไม่แน่ใจ ข้ามไปอันถัดไป
    }
    return null;
  }
  function scroller(){
    var best = null, bh = 0;
    document.querySelectorAll('*').forEach(function(el){
      var e = el.scrollHeight - el.clientHeight;
      if (e > bh && /(auto|scroll)/.test(getComputedStyle(el).overflowY)) { bh = e; best = el; }
    });
    return best;
  }
  function go(){
    if (userMoved) return;
    var sc = scroller(), f = find();
    if (f) {
      var r = document.createRange();
      r.setStart(f[0], 0); r.setEnd(f[0], Math.min(f[0].data.length, 1));
      var top = r.getBoundingClientRect().top;
      if (sc) sc.scrollTop += top - sc.getBoundingClientRect().top - 8;
      else window.scrollBy(0, top - 8);
      return;
    }
    if (sc) sc.scrollTop = d.r * (sc.scrollHeight - sc.clientHeight);
  }
  [0, 150, 450, 1000, 1800].forEach(function(t){ setTimeout(go, t); });
})(%s)
"""


def _edit_jump_setup(editor):
    st = {"data": None, "loaded": False, "done": False}

    def try_jump():
        if st["done"] or st["data"] is None or not st["loaded"]:
            return
        st["done"] = True
        try:
            editor.web.eval(_JUMP_JS % st["data"])
        except Exception:
            pass

    def got(res):
        st["data"] = res if isinstance(res, str) and res.startswith("{") else None
        try_jump()

    def loaded(ed):
        if ed is editor:
            st["loaded"] = True
            try_jump()

    gui_hooks.editor_did_load_note.append(loaded)

    def cleanup(*_a):
        try:
            gui_hooks.editor_did_load_note.remove(loaded)
        except Exception:
            pass

    try:
        editor.parentWindow.destroyed.connect(cleanup)
        editor.parentWindow.finished.connect(cleanup)
    except Exception:
        pass
    # ถ้าโน้ตโหลดไปแล้วก่อนเรามาถึง
    if getattr(editor, "note", None) is not None:
        QTimer.singleShot(300, lambda: (st.update(loaded=True), try_jump()))
    mw.reviewer.web.evalWithCallback(_ANCHOR_JS, got)


gui_hooks.editor_did_init.append(_on_editor_init)
gui_hooks.reviewer_did_show_question.append(_restore_scroll)
gui_hooks.reviewer_did_show_answer.append(_restore_scroll)
