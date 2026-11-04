"""
Live Preview ในหน้า Add
-----------------------
แสดงแผงพรีวิวการ์ด (ด้านหน้า/ด้านหลัง) ข้างช่องกรอกในหน้าต่าง Add
อัปเดตเองขณะพิมพ์ รองรับหลาย card template, Cloze, รูป, MathJax และ night mode

ปุ่มในแถบเครื่องมือของ editor:
  • พรีวิว      – แสดง/ซ่อนแผง (Ctrl+Shift+Y)
  • $→MathJax  – แปลง $...$ / $$...$$ เป็น \\(...\\) / \\[...\\] ที่ Anki แสดงผลได้
"""

import html
import json
import re

from aqt import gui_hooks, mw
from aqt.addcards import AddCards
from aqt.qt import QBoxLayout, QSplitter, Qt, QTimer, QVBoxLayout, QWidget
from aqt.utils import tooltip
from aqt.webview import AnkiWebView

try:
    from aqt.theme import theme_manager
except Exception:  # pragma: no cover
    theme_manager = None

ADDON = __name__.split(".")[0]
PREVIEW_KEY = "Ctrl+Shift+Y"


# ---------------------------------------------------------------- config

def _cfg():
    try:
        return mw.addonManager.getConfig(ADDON) or {}
    except Exception:
        return {}


def _set_cfg(key, value):
    c = _cfg()
    c[key] = value
    try:
        mw.addonManager.writeConfig(ADDON, c)
    except Exception:
        pass


# ---------------------------------------------------------------- $ -> MathJax

_DISPLAY = re.compile(r"(?<!\\)\$\$(.+?)(?<!\\)\$\$", re.S)
_INLINE = re.compile(r"(?<![\\$])\$(?![\s$])([^$]+?)(?<![\s\\])\$(?!\$)")


def dollar_to_mathjax(text: str) -> str:
    text = _DISPLAY.sub(lambda m: "\\[" + m.group(1) + "\\]", text)
    text = _INLINE.sub(lambda m: "\\(" + m.group(1) + "\\)", text)
    return text


def convert_dollars(editor):
    def go():
        note = editor.note
        if note is None:
            return
        changed = 0
        for i, val in enumerate(note.fields):
            new = dollar_to_mathjax(val)
            if new != val:
                note.fields[i] = new
                changed += 1
        if not changed:
            tooltip("ไม่พบ $...$ ในการ์ดนี้")
            return
        if not getattr(editor, "addMode", False) and getattr(note, "id", 0):
            mw.col.update_note(note)
        editor.loadNoteKeepingFocus()
        tooltip(f"แปลงสูตรเป็นรูปแบบ MathJax แล้ว ({changed} ช่อง)")
        _refresh_editor(editor)

    editor.call_after_note_saved(go)


# ---------------------------------------------------------------- rendering

def _note_type(note):
    fn = getattr(note, "note_type", None) or getattr(note, "model")
    return fn()


def _body_class(ord_):
    try:
        return theme_manager.body_classes_for_card_ord(ord_)
    except Exception:
        return f"card card{ord_ + 1}"


def _prep(text):
    fn = getattr(mw, "prepare_card_text_for_display", None)
    try:
        return fn(text) if fn else text
    except Exception:
        return text


def render_note(note):
    nt = _note_type(note)
    if nt.get("type") == 1:  # Cloze
        nums = sorted(note.cloze_numbers_in_fields()) or [1]
        items = [(n - 1, f"c{n}") for n in nums]
    else:
        items = [(t["ord"], t["name"]) for t in nt["tmpls"]]

    cards = []
    for ord_, label in items:
        try:
            card = note.ephemeral_card(ord=ord_)
            front = _prep(card.question())
            back = _prep(card.answer())
        except Exception as e:
            front = f"<pre>{html.escape(str(e))}</pre>"
            back = ""
        cards.append(
            {"label": label, "front": front, "back": back,
             "bodyClass": _body_class(ord_)}
        )
    return cards


PAGE = r"""
<style>
  #cp-bar { position: sticky; top: 0; z-index: 50; display: flex; flex-wrap: wrap;
    gap: 4px; padding: 6px 8px; text-align: left; font: 12px system-ui, sans-serif;
    background: var(--canvas, var(--window-bg, #f0f0f0));
    border-bottom: 1px solid var(--border, rgba(128,128,128,.35)); }
  #cp-bar:empty { display: none; }
  #cp-bar button { font: inherit; padding: 2px 10px; border-radius: 4px; cursor: pointer;
    border: 1px solid var(--border, rgba(128,128,128,.5)); background: transparent;
    color: var(--fg, var(--text-fg, inherit)); }
  #cp-bar button.on { background: var(--button-primary-bg, #3b82f6); color: #fff;
    border-color: transparent; }
  .cp-label { margin: 14px 0 6px; padding: 2px 8px; text-align: left;
    font: 600 13px system-ui, sans-serif; opacity: .6;
    border-left: 3px solid currentColor; }
  .cp-label:first-child { margin-top: 4px; }
  .cp-back { padding-bottom: 24px; }
</style>
<div id="cp-bar"></div>
<div id="qa"></div>
<script>
  var cpBase = document.body.className;
  var cpData = null, cpSel = 0;
  window.MathJax = {
    tex: { inlineMath: [["\\(", "\\)"]], displayMath: [["\\[", "\\]"]],
           processEscapes: false, processEnvironments: false, processRefs: false },
    startup: { typeset: false, ready: function () {
      MathJax.startup.defaultReady();
      MathJax.startup.promise.then(function () { cpTypeset(); });
    } },
    options: { ignoreHtmlClass: "tex2jax_ignore", processHtmlClass: "tex2jax_process" }
  };
  function cpTypeset() {
    var el = document.getElementById("qa");
    if (window.MathJax && MathJax.typesetPromise) {
      MathJax.typesetPromise([el]).catch(function () {});
    }
  }
  function cpRender(data) {
    cpData = data;
    cpSel = Math.max(0, Math.min(data.sel, data.cards.length - 1));
    cpShow();
  }
  function cpPick(i) { cpSel = i; pycmd("csvprev:sel:" + i); cpShow(); }
  function cpShow() {
    var cards = cpData.cards, c = cards[cpSel];
    var bar = document.getElementById("cp-bar");
    bar.innerHTML = "";
    if (cards.length > 1) {
      cards.forEach(function (card, i) {
        var b = document.createElement("button");
        b.textContent = card.label;
        if (i === cpSel) b.className = "on";
        b.onclick = function () { cpPick(i); };
        bar.appendChild(b);
      });
    }
    document.body.className = cpBase + " " + c.bodyClass;
    var qa = document.getElementById("qa");
    if (window.MathJax && MathJax.typesetClear) {
      try { MathJax.typesetClear([qa]); } catch (e) {}
    }
    qa.innerHTML =
      '<div class="cp-label">ด้านหน้า</div><div class="cp-front">' + c.front + "</div>" +
      '<div class="cp-label">ด้านหลัง</div><div class="cp-back">' + c.back + "</div>";
    cpTypeset();
  }
</script>
<script src="/_anki/js/vendor/mathjax/tex-chtml-full.js"></script>
"""


def _make_web(parent):
    try:
        from aqt.webview import AnkiWebViewKind

        return AnkiWebView(parent, kind=AnkiWebViewKind.PREVIEWER)
    except Exception:
        return AnkiWebView(parent, title="csv_drop_preview")


class PreviewPanel(QWidget):
    def __init__(self, addcards: AddCards):
        super().__init__(addcards)
        self.addcards = addcards
        self.editor = addcards.editor
        self.sel = 0
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.web = _make_web(self)
        self.web.set_bridge_command(self._on_bridge, self)
        self.web.stdHtml(PAGE, css=["css/reviewer.css"], context=self)
        lay.addWidget(self.web)
        self.setMinimumWidth(260)

        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.setInterval(120)
        self._timer.timeout.connect(self._do_refresh)

    def refresh(self):
        if self.isVisible():
            self._timer.start()

    def _do_refresh(self):
        note = getattr(self.editor, "note", None)
        if note is None:
            return
        try:
            cards = render_note(note)
        except Exception as e:
            cards = [{"label": "error", "bodyClass": "card",
                      "front": f"<pre>{html.escape(str(e))}</pre>", "back": ""}]
        self.web.eval(f"cpRender({json.dumps({'cards': cards, 'sel': self.sel})});")

    def _on_bridge(self, cmd):
        if cmd.startswith("csvprev:sel:"):
            try:
                self.sel = int(cmd.rsplit(":", 1)[1])
            except ValueError:
                pass
        # คำสั่งอื่น เช่นปุ่มเล่นเสียง ไม่ต้องทำอะไร

    def set_shown(self, shown: bool):
        self.setVisible(shown)
        if shown:
            _widen(self.addcards)
            self.refresh()


# ---------------------------------------------------------------- wiring

_panels = []


def _live_panels():
    alive = []
    for p in _panels:
        try:
            p.objectName()  # RuntimeError ถ้าหน้าต่างถูกปิดไปแล้ว
            alive.append(p)
        except RuntimeError:
            pass
    _panels[:] = alive
    return alive


def _panel_for_editor(editor):
    for p in _live_panels():
        if p.editor is editor:
            return p
    return None


def _refresh_editor(editor):
    p = _panel_for_editor(editor)
    if p:
        p.refresh()


def _refresh_note(note):
    for p in _live_panels():
        if getattr(p.editor, "note", None) is note:
            p.refresh()


def _widen(addcards):
    try:
        if addcards.width() >= 1000:
            return
        screen = addcards.screen().availableGeometry()
        new_w = min(addcards.width() + 460, screen.width() - 40)
        addcards.resize(new_w, addcards.height())
    except Exception:
        pass


def _embed(addcards, panel):
    fields = addcards.form.fieldsArea
    parent = fields.parentWidget()
    lay = parent.layout()
    idx = lay.indexOf(fields) if lay else -1
    if idx < 0 or not isinstance(lay, QBoxLayout):
        raise RuntimeError("unexpected Add window layout")
    stretch = lay.stretch(idx)
    lay.removeWidget(fields)
    split = QSplitter(Qt.Orientation.Horizontal, parent)
    split.setChildrenCollapsible(False)
    split.addWidget(fields)
    split.addWidget(panel)
    split.setStretchFactor(0, 3)
    split.setStretchFactor(1, 2)
    lay.insertWidget(idx, split, max(stretch, 1))


def on_add_cards_init(addcards):
    panel = PreviewPanel(addcards)
    try:
        _embed(addcards, panel)
    except Exception:
        # ถ้าโครงหน้าต่าง Add เปลี่ยนในเวอร์ชันใหม่ ให้เปิดเป็นหน้าต่างแยกแทน
        panel.setParent(addcards, Qt.WindowType.Tool)
        panel.setWindowTitle("พรีวิวการ์ด")
        panel.resize(460, addcards.height())
    _panels.append(panel)
    panel.set_shown(bool(_cfg().get("preview_enabled", True)))


def toggle_preview(editor):
    p = _panel_for_editor(editor)
    if p is None:
        tooltip("พรีวิวใช้ได้ในหน้าต่าง Add")
        return
    shown = not p.isVisible()
    p.set_shown(shown)
    _set_cfg("preview_enabled", shown)


def on_init_buttons(buttons, editor):
    if isinstance(getattr(editor, "parentWindow", None), AddCards):
        buttons.append(editor.addButton(
            icon=None, cmd="csvdrop_preview", func=toggle_preview,
            tip=f"แสดง/ซ่อนพรีวิวการ์ด ({PREVIEW_KEY})",
            label="พรีวิว", keys=PREVIEW_KEY, disables=False,
        ))
    buttons.append(editor.addButton(
        icon=None, cmd="csvdrop_dollar", func=convert_dollars,
        tip="แปลง $...$ และ $$...$$ เป็น \\(...\\) / \\[...\\] ให้ Anki แสดงสูตรได้",
        label="$→MathJax", disables=False,
    ))
    return buttons


def on_unfocus(changed, note, _ord):
    _refresh_note(note)
    return changed


gui_hooks.add_cards_did_init.append(on_add_cards_init)
gui_hooks.editor_did_init_buttons.append(on_init_buttons)
gui_hooks.editor_did_fire_typing_timer.append(_refresh_note)
gui_hooks.editor_did_unfocus_field.append(on_unfocus)
gui_hooks.editor_did_load_note.append(_refresh_editor)
