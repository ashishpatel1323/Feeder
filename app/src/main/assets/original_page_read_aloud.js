// Read aloud support for the original web page shown inside Feeder.
// Builds the page's visible text, reports it to the app, highlights the spoken
// sentence and word, and turns taps into "read from here" offsets.
(function () {
  if (window.__feederReadAloud) {
    window.__feederReadAloud.extract();
    return;
  }

  var SKIP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, TITLE: 1, IFRAME: 1, CANVAS: 1,
    VIDEO: 1, AUDIO: 1, SELECT: 1, OPTION: 1, TEXTAREA: 1, INPUT: 1, BUTTON: 1, svg: 1, SVG: 1,
  };
  var INLINE = { inline: 1, contents: 1, ruby: 1 };
  var SENTENCE_COLOR = 'rgba(128, 128, 128, 0.25)';
  var WORD_COLOR = '#FFD970';
  var WORD_TEXT = '#1B1B1F';

  // Each entry: text node, its start in the spoken text, and collapsed index -> raw node offset
  var entries = [];
  var text = '';
  var reading = false;
  var supportsHighlights = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight !== 'undefined';
  var overlay = null;
  var lastSentence = null;
  var lastWord = null;
  var pill = null;

  function bridge() {
    return window.FeederReadAloud;
  }

  function hidden(element) {
    if (!element.getClientRects().length) return true;
    var style = getComputedStyle(element);
    return style.visibility === 'hidden' || style.display === 'none';
  }

  // Nearest ancestor that starts a new line (a paragraph, list item, table cell...)
  function blockOf(element) {
    var current = element;
    while (current && current !== document.body) {
      var display = getComputedStyle(current).display;
      if (!INLINE[display] && display.indexOf('inline') !== 0) return current;
      current = current.parentElement;
    }
    return document.body;
  }

  // Nearest ancestor laid out as its own box, like inline-block tabs or buttons
  function boxOf(element) {
    var current = element;
    while (current && current !== document.body) {
      if (!INLINE[getComputedStyle(current).display]) return current;
      current = current.parentElement;
    }
    return document.body;
  }

  function extract() {
    entries = [];
    text = '';
    if (!document.body) return;
    var lastBlock = null;
    var lastBox = null;
    var visible = new Map();
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          if (SKIP[node.nodeName] || node.id === 'feeder-read-aloud-overlay' || node.id === 'feeder-read-aloud-pill') {
            return NodeFilter.FILTER_REJECT;
          }
          return NodeFilter.FILTER_SKIP;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    var node;
    while ((node = walker.nextNode())) {
      var raw = node.nodeValue;
      if (!raw) continue;
      // Spaces between inline elements matter, but not at the start of a block
      if (!/\S/.test(raw) && (!text.length || /\s$/.test(text))) continue;
      var parent = node.parentElement;
      if (!parent) continue;
      var isHidden = visible.get(parent);
      if (isHidden === undefined) {
        isHidden = hidden(parent);
        visible.set(parent, isHidden);
      }
      if (isHidden) continue;
      var block = blockOf(parent);
      var box = boxOf(parent);
      if (block !== lastBlock && text.length) {
        text = text.replace(/ +$/, '');
        if (!/\n\n$/.test(text)) text += '\n\n';
      } else if (box !== lastBox && text.length && !/\s$/.test(text)) {
        text += ' ';
      }
      lastBlock = block;
      lastBox = box;
      var map = [];
      var collapsed = '';
      var lastSpace = text.length === 0 || /\s$/.test(text);
      for (var i = 0; i < raw.length; i++) {
        var ch = raw[i];
        if (/\s/.test(ch)) {
          if (lastSpace) continue;
          ch = ' ';
          lastSpace = true;
        } else {
          lastSpace = false;
        }
        collapsed += ch;
        map.push(i);
      }
      if (!collapsed.length) continue;
      entries.push({ node: node, start: text.length, map: map, length: raw.length });
      text += collapsed;
    }
    text = text.replace(/\s+$/, '');
    var app = bridge();
    if (app) app.onText(text);
  }

  // Finds the entry holding spoken offset [offset], by binary search
  function entryAt(offset) {
    var low = 0;
    var high = entries.length - 1;
    var found = -1;
    while (low <= high) {
      var mid = (low + high) >> 1;
      if (entries[mid].start <= offset) {
        found = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return found;
  }

  function point(offset, isEnd) {
    var index = entryAt(offset);
    if (index < 0) return null;
    var entry = entries[index];
    var local = offset - entry.start;
    if (local >= entry.map.length) {
      // In the separator after this node
      if (isEnd) return { node: entry.node, offset: entry.length };
      var next = entries[index + 1];
      return next ? { node: next.node, offset: next.map[0] } : null;
    }
    if (isEnd && local > 0) return { node: entry.node, offset: entry.map[local - 1] + 1 };
    return { node: entry.node, offset: entry.map[local] };
  }

  function rangeFor(start, end) {
    if (end <= start) return null;
    var from = point(start, false);
    var to = point(end, true);
    if (!from || !to || !from.node.isConnected || !to.node.isConnected) return null;
    var range = document.createRange();
    try {
      range.setStart(from.node, Math.min(from.offset, from.node.length));
      range.setEnd(to.node, Math.min(to.offset, to.node.length));
    } catch (e) {
      return null;
    }
    return range;
  }

  function installStyles() {
    if (document.getElementById('feeder-read-aloud-style')) return;
    var style = document.createElement('style');
    style.id = 'feeder-read-aloud-style';
    style.textContent =
      '::highlight(feeder-sentence){background-color:' + SENTENCE_COLOR + ';}' +
      '::highlight(feeder-word){background-color:' + WORD_COLOR + ';color:' + WORD_TEXT + ';}' +
      '#feeder-read-aloud-overlay{position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483646;}' +
      '#feeder-read-aloud-overlay div{position:absolute;border-radius:3px;}' +
      '#feeder-read-aloud-pill{position:absolute;z-index:2147483647;padding:8px 14px;border-radius:18px;' +
      'background:#1B1B1F;color:#fff;font:500 14px/1.2 sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.35);' +
      'white-space:nowrap;cursor:pointer;-webkit-user-select:none;user-select:none;}';
    (document.head || document.documentElement).appendChild(style);
  }

  // Fallback for WebViews without the CSS Custom Highlight API: boxes drawn over the text
  function drawOverlay(sentence, word) {
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.id = 'feeder-read-aloud-overlay';
      document.body.appendChild(overlay);
    }
    overlay.innerHTML = '';
    function paint(range, color, opacity) {
      if (!range) return;
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        var r = rects[i];
        var box = document.createElement('div');
        box.style.left = r.left + window.scrollX + 'px';
        box.style.top = r.top + window.scrollY + 'px';
        box.style.width = r.width + 'px';
        box.style.height = r.height + 'px';
        box.style.background = color;
        box.style.opacity = opacity;
        overlay.appendChild(box);
      }
    }
    paint(sentence, SENTENCE_COLOR, 1);
    paint(word, WORD_COLOR, 0.55);
  }

  function clearHighlight() {
    lastSentence = null;
    lastWord = null;
    if (supportsHighlights) {
      CSS.highlights.delete('feeder-sentence');
      CSS.highlights.delete('feeder-word');
    }
    if (overlay) overlay.innerHTML = '';
  }

  function scrollToRange(range) {
    var rect = range.getBoundingClientRect();
    if (!rect.height && !rect.width) return;
    var height = window.innerHeight;
    if (rect.top >= height * 0.2 && rect.bottom <= height * 0.7) return;
    var element = range.startContainer.parentElement;
    var scroller = element;
    // Scroll whichever container actually scrolls, falling back to the window
    while (scroller && scroller !== document.body && scroller !== document.documentElement) {
      var style = getComputedStyle(scroller);
      if (/(auto|scroll)/.test(style.overflowY) && scroller.scrollHeight > scroller.clientHeight) break;
      scroller = scroller.parentElement;
    }
    if (scroller && scroller !== document.body && scroller !== document.documentElement) {
      var box = scroller.getBoundingClientRect();
      scroller.scrollBy({ top: rect.top - box.top - box.height * 0.35, behavior: 'smooth' });
    } else {
      window.scrollBy({ top: rect.top - height * 0.35, behavior: 'smooth' });
    }
  }

  function highlight(sentenceStart, sentenceEnd, wordStart, wordEnd, follow) {
    installStyles();
    hidePill();
    var sentence = rangeFor(sentenceStart, sentenceEnd);
    var word = wordStart >= 0 ? rangeFor(wordStart, wordEnd) : null;
    lastSentence = sentence;
    lastWord = word;
    if (supportsHighlights) {
      if (sentence) CSS.highlights.set('feeder-sentence', new Highlight(sentence));
      else CSS.highlights.delete('feeder-sentence');
      if (word) CSS.highlights.set('feeder-word', new Highlight(word));
      else CSS.highlights.delete('feeder-word');
    } else {
      drawOverlay(sentence, word);
    }
    var target = word || sentence;
    if (follow && target) scrollToRange(target);
  }

  function caretAt(x, y) {
    if (document.caretPositionFromPoint) {
      var position = document.caretPositionFromPoint(x, y);
      if (position) return { node: position.offsetNode, offset: position.offset };
    }
    if (document.caretRangeFromPoint) {
      var range = document.caretRangeFromPoint(x, y);
      if (range) return { node: range.startContainer, offset: range.startOffset };
    }
    return null;
  }

  // Spoken offset of the word under (x, y), or -1
  function offsetAt(x, y) {
    var caret = caretAt(x, y);
    if (!caret || caret.node.nodeType !== Node.TEXT_NODE) return -1;
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      if (entry.node !== caret.node) continue;
      var local = 0;
      while (local < entry.map.length - 1 && entry.map[local + 1] <= caret.offset) local++;
      var offset = entry.start + local;
      while (offset > 0 && /\S/.test(text[offset - 1])) offset--;
      while (offset < text.length && /\s/.test(text[offset])) offset++;
      return offset < text.length ? offset : -1;
    }
    return -1;
  }

  function wordEnd(offset) {
    var end = offset;
    while (end < text.length && /\S/.test(text[end])) end++;
    return end;
  }

  function hidePill() {
    if (pill) {
      pill.remove();
      pill = null;
    }
    if (supportsHighlights) CSS.highlights.delete('feeder-tap');
  }

  function showPill(offset, x, y) {
    installStyles();
    hidePill();
    var word = rangeFor(offset, wordEnd(offset));
    if (word) {
      if (supportsHighlights) {
        var style = document.getElementById('feeder-read-aloud-style');
        if (style.textContent.indexOf('feeder-tap') < 0) {
          style.textContent += '::highlight(feeder-tap){background-color:' + WORD_COLOR + ';color:' + WORD_TEXT + ';}';
        }
        CSS.highlights.set('feeder-tap', new Highlight(word));
      }
    }
    var rect = word ? word.getBoundingClientRect() : { left: x, top: y, bottom: y };
    pill = document.createElement('div');
    pill.id = 'feeder-read-aloud-pill';
    pill.textContent = window.__feederReadFromHereLabel || 'Read aloud from here';
    document.body.appendChild(pill);
    var width = pill.offsetWidth;
    var left = Math.max(8, Math.min(rect.left + window.scrollX, window.scrollX + window.innerWidth - width - 8));
    var top = rect.top + window.scrollY - pill.offsetHeight - 10;
    if (rect.top < pill.offsetHeight + 20) top = rect.bottom + window.scrollY + 10;
    pill.style.left = left + 'px';
    pill.style.top = top + 'px';
    pill.addEventListener(
      'click',
      function (event) {
        event.preventDefault();
        event.stopPropagation();
        hidePill();
        var app = bridge();
        if (app) app.onReadFrom(offset);
      },
      true,
    );
  }

  document.addEventListener(
    'click',
    function (event) {
      if (pill && (event.target === pill || pill.contains(event.target))) return;
      if (event.target.closest && event.target.closest('a[href], button, input, select, textarea, summary, label')) {
        hidePill();
        return;
      }
      var offset = offsetAt(event.clientX, event.clientY);
      if (offset < 0) {
        hidePill();
        return;
      }
      var app = bridge();
      if (reading) {
        if (app) app.onReadFrom(offset);
      } else {
        showPill(offset, event.clientX, event.clientY);
      }
    },
    true,
  );

  var touchStartY = null;
  document.addEventListener(
    'touchstart',
    function (event) {
      touchStartY = event.touches.length ? event.touches[0].clientY : null;
    },
    { passive: true, capture: true },
  );
  document.addEventListener(
    'touchmove',
    function (event) {
      if (touchStartY === null || !event.touches.length) return;
      if (Math.abs(event.touches[0].clientY - touchStartY) > 12) {
        touchStartY = null;
        var app = bridge();
        if (app) app.onUserScroll();
      }
    },
    { passive: true, capture: true },
  );

  window.addEventListener('resize', function () {
    if (!supportsHighlights && (lastSentence || lastWord)) drawOverlay(lastSentence, lastWord);
  });

  window.__feederReadAloud = {
    extract: extract,
    highlight: highlight,
    clear: clearHighlight,
    setReading: function (value) {
      reading = !!value;
      if (reading) hidePill();
    },
  };
  extract();
})();
