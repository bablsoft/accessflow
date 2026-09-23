// AccessFlow public site — vanilla JS for tab switching, copy button, how-it-works stepper,
// the theme toggle and the AI chat bubble.

(function () {
  'use strict';

  function initInstallTabs() {
    var buttons = document.querySelectorAll('.install-tab');
    var panes = document.querySelectorAll('[data-install-pane]');
    if (!buttons.length || !panes.length) return;

    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var target = btn.getAttribute('data-install-tab');
        buttons.forEach(function (b) {
          b.classList.toggle('active', b === btn);
          b.setAttribute('aria-selected', b === btn ? 'true' : 'false');
        });
        panes.forEach(function (p) {
          var match = p.getAttribute('data-install-pane') === target;
          p.hidden = !match;
        });
      });
    });
  }

  function initCopyButtons() {
    var buttons = document.querySelectorAll('.copy-btn');
    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var pane = btn.closest('[data-install-pane]') || btn.closest('.code-block');
        if (!pane) return;
        var pre = pane.querySelector('pre');
        if (!pre) return;
        var text = pre.innerText;
        var done = function () {
          btn.classList.add('copied');
          btn.setAttribute('aria-label', 'Copied');
          var label = btn.querySelector('[data-copy-label]');
          var original;
          if (label) {
            original = label.textContent;
            label.textContent = 'copied';
          }
          setTimeout(function () {
            btn.classList.remove('copied');
            btn.setAttribute('aria-label', 'Copy to clipboard');
            if (label && original) label.textContent = original;
          }, 1500);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done).catch(function () {
            fallbackCopy(text);
            done();
          });
        } else {
          fallbackCopy(text);
          done();
        }
      });
    });
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'absolute';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (_) {}
    document.body.removeChild(ta);
  }

  function initFlowStepper() {
    var steps = Array.prototype.slice.call(document.querySelectorAll('.flow-step'));
    var stages = Array.prototype.slice.call(document.querySelectorAll('[data-flow-stage]'));
    if (!steps.length || !stages.length) return;

    var active = 0;
    var timer = null;
    var INTERVAL = 5200;

    function render() {
      steps.forEach(function (s, i) {
        s.classList.toggle('active', i === active);
        s.setAttribute('aria-selected', i === active ? 'true' : 'false');
      });
      stages.forEach(function (st, i) {
        st.hidden = i !== active;
      });
    }

    function start() {
      stop();
      timer = setInterval(function () {
        active = (active + 1) % steps.length;
        render();
      }, INTERVAL);
    }

    function stop() {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    }

    steps.forEach(function (s, i) {
      s.addEventListener('click', function () {
        active = i;
        render();
        stop();
      });
    });

    render();
    start();
  }

  // The theme the page is actually rendering: the explicit <html data-theme> set by
  // the inline bootstrap script / the toggle, else the OS preference — the same
  // rule styles.css applies. Shared by the toggle and the chat bubble.
  function resolveTheme() {
    var attr = document.documentElement.getAttribute('data-theme');
    if (attr === 'light' || attr === 'dark') return attr;
    var mql = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
    return mql && mql.matches ? 'light' : 'dark';
  }

  // Cloudflare AI Search chat bubble (https://github.com/cloudflare/ai-search-snippet).
  // Injected here rather than authored into the markup because the site has no build
  // step: every page hand-copies its <head> and footer, and websitePages.test.ts pins
  // the footer byte-identical across all of them. One function is the single source
  // of truth for the version pin, the endpoint and the copy. The bundle is a native
  // web component; its colours come from the `chat-bubble-snippet { --search-snippet-* }`
  // block in styles.css, which maps them onto the site tokens, so the widget follows
  // the light/dark toggle without any per-theme code here beyond the `theme`
  // attribute (which only drives the widget's own color-scheme).
  //
  // Bumping the widget: change the /assets/vX.Y.Z/ segment, `curl -I` the new URL to
  // confirm chat.accessflow.io serves it, and re-run the frontend website tests —
  // websiteCsp.test.ts checks this origin against the CSP in _headers.
  var CHAT_SCRIPT = 'https://chat.accessflow.io/assets/v0.0.43/search-snippet.chat.es.js';
  var CHAT_API = 'https://chat.accessflow.io/';

  function syncChatTheme(theme) {
    var bubble = document.querySelector('chat-bubble-snippet');
    if (bubble) bubble.setAttribute('theme', theme);
  }

  // The bubble keeps its conversation in memory only (the widget's localStorage
  // session store belongs to its sibling <chat-page-snippet>), and this is a
  // multi-page site: every link — including the docs citations the assistant
  // itself emits — would otherwise wipe the chat and close the window. So the
  // page snapshots {open, messages} on the way out and puts them back on the way
  // in. sessionStorage, not localStorage, on purpose: the conversation lives as
  // long as the tab and never outlives it.
  //
  // The restore leans on three v0.0.43 members the widget does not document —
  // toggleChat(), isExpanded and chatView.setMessages() (the view is only created
  // on first expand, so the window has to be toggled open before it can be fed).
  // Every access is guarded and the whole thing fails soft: a bump that renames
  // any of them degrades to a fresh chat, never to a broken page. Re-verify after
  // bumping (README → "The AI chat bubble").
  var CHAT_STORAGE_KEY = 'accessflow.chat';
  var CHAT_MAX_MESSAGES = 40;

  function saveChat(bubble) {
    try {
      if (typeof bubble.getMessages !== 'function') return;
      var messages = bubble.getMessages().filter(function (m) {
        return m && typeof m.content === 'string' && m.content !== '';
      }).slice(-CHAT_MAX_MESSAGES);
      // A user turn whose reply was still streaming would be re-sent as context
      // with no answer; drop it rather than persist half a conversation.
      var last = messages[messages.length - 1];
      if (last && last.role === 'user') messages.pop();
      sessionStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify({
        open: !!bubble.isExpanded,
        messages: messages,
      }));
    } catch (e) { /* private mode or storage full — ignore */ }
  }

  function restoreChat(bubble) {
    var saved;
    try { saved = JSON.parse(sessionStorage.getItem(CHAT_STORAGE_KEY)); } catch (e) { saved = null; }
    if (!saved || (!saved.open && !(saved.messages && saved.messages.length))) return;
    try {
      if (typeof bubble.toggleChat !== 'function') return;
      bubble.toggleChat();
      var view = bubble.chatView;
      if (view && typeof view.setMessages === 'function' && saved.messages && saved.messages.length) {
        view.setMessages(saved.messages);
      }
      // Both toggles land in one frame, so a closed window never flashes open.
      if (!saved.open) bubble.toggleChat();
    } catch (e) {
      try { sessionStorage.removeItem(CHAT_STORAGE_KEY); } catch (_) { /* ignore */ }
    }
  }

  function initChatBubble() {
    if (document.querySelector('chat-bubble-snippet')) return;

    var bubble = document.createElement('chat-bubble-snippet');
    bubble.setAttribute('api-url', CHAT_API);
    bubble.setAttribute('hide-branding', 'true');
    bubble.setAttribute('theme', resolveTheme());
    bubble.setAttribute('placeholder', 'Ask about AccessFlow…');

    // Copy goes through the `translations` JS property, not the attribute of the
    // same name: in v0.0.43 the attribute only reaches the bubble header, while the
    // inner chat view (empty state, avatars, loading lines) is handed the property
    // override alone. The setter also exists only once the module has upgraded the
    // element, so it has to wait for the definition.
    var translations = {
      chatTitle: 'Ask AccessFlow',
      chatEmptyTitle: 'Ask anything about AccessFlow',
      chatEmptyDescription: 'Answers come from the documentation on this site — installing, connectors, review workflows, security.',
      assistantAvatar: 'AF',
      loadingMessages: ['Reading the docs…', 'Checking the guides…', 'Almost there…'],
    };
    if (window.customElements && customElements.whenDefined) {
      customElements.whenDefined('chat-bubble-snippet').then(function () {
        bubble.translations = translations;
        restoreChat(bubble);
      });
    }

    // Snapshot on every way off the page (pagehide covers links, back/forward and
    // bfcache) and after each completed reply, so a crash mid-visit loses at most
    // the turn in flight.
    window.addEventListener('pagehide', function () { saveChat(bubble); });
    bubble.addEventListener('message', function () { saveChat(bubble); });

    var script = document.createElement('script');
    script.type = 'module';
    script.src = CHAT_SCRIPT;
    // A blocked or failed CDN load must not leave an inert element behind: the
    // custom element only renders once the module defines it, so remove it.
    script.addEventListener('error', function () {
      if (bubble.parentNode) bubble.parentNode.removeChild(bubble);
    });

    document.body.appendChild(bubble);
    document.head.appendChild(script);
  }

  function initThemeToggle() {
    var STORAGE_KEY = 'accessflow.theme';
    var root = document.documentElement;
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    if (!buttons.length) return;

    var mql = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;

    // A figure only has both variants when the authored markup points its <source>
    // and its <img> at different files. capture.ts writes both twins for every
    // screen, so nothing on the site is light-only today; a figure that carries the
    // same -light.webp in both attributes has no -dark.webp on disk, and rewriting
    // it would be a guaranteed 404. Read the pairing off the authored attributes
    // once and cache it: after a swap the two can legitimately match, so this
    // cannot be re-derived later.
    function hasBothThemeVariants(pic) {
      if (pic.getAttribute('data-theme-pair') === null) {
        var src = pic.querySelector('source[srcset]');
        var img = pic.querySelector('img[src]');
        var paired = !!src && !!img &&
          src.getAttribute('srcset') !== img.getAttribute('src');
        pic.setAttribute('data-theme-pair', paired ? '1' : '0');
      }
      return pic.getAttribute('data-theme-pair') === '1';
    }

    function swapDocsImages(theme) {
      // <picture><source media="(prefers-color-scheme: light)"> tracks the OS, not
      // our data-theme attribute, so an explicit toggle has to rewrite the markup.
      //
      // Rewriting <img src> alone is NOT enough: whenever a <source> media query
      // matches, it wins over img.src and the image never changes. That left the
      // toggle silently broken for anyone on a light-themed OS. Rewrite both.
      var want  = theme === 'light' ? '-light.webp' : '-dark.webp';
      var other = theme === 'light' ? '-dark.webp'  : '-light.webp';

      document.querySelectorAll('picture').forEach(function (pic) {
        if (!hasBothThemeVariants(pic)) return;
        pic.querySelectorAll('source[srcset]').forEach(function (src) {
          if (src.srcset.indexOf(other) !== -1) {
            src.srcset = src.srcset.replace(other, want);
          }
        });
        var img = pic.querySelector('img[src]');
        if (img && img.src.indexOf(other) !== -1) {
          img.src = img.src.replace(other, want);
        }
      });
    }

    function syncButtons() {
      var t = resolveTheme();
      var goingTo = t === 'light' ? 'dark' : 'light';
      buttons.forEach(function (btn) {
        btn.setAttribute('aria-pressed', t === 'light' ? 'true' : 'false');
        btn.setAttribute('aria-label', 'Switch to ' + goingTo + ' theme');
      });
      swapDocsImages(t);
      syncChatTheme(t);
    }

    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var next = resolveTheme() === 'light' ? 'dark' : 'light';
        root.setAttribute('data-theme', next);
        try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* private mode — ignore */ }
        syncButtons();
      });
    });

    if (mql && mql.addEventListener) {
      mql.addEventListener('change', function () {
        // OS change only propagates to visitors who haven't made an explicit choice.
        var stored;
        try { stored = localStorage.getItem(STORAGE_KEY); } catch (e) { stored = null; }
        if (!stored) syncButtons();
      });
    }

    syncButtons();
  }


  // Docs used to be one page at /docs/ with ~50 in-page anchors. It is now split
  // into per-chapter URLs, but AccessFlow is self-hosted: every already-released
  // frontend links to /docs/#cfg-<x> from its in-app "View docs" buttons, and
  // those installs never update. This forwarder is therefore PERMANENT, not a
  // migration aid. Keep it in sync with frontend/src/config/docs.ts.
  // NOTE: '#configuration' is deliberately absent. It used to head one giant
  // section that is now eight chapters, so there is no single right destination
  // — leaving it unmapped keeps the visitor on this hub, which lists them all.
  var LEGACY_DOCS_ANCHORS = {
      'guide-deployment-approval': '/docs/guides/deployment-approval/',
      'guide-help-assistant': '/docs/guides/help-assistant/',
      'cfg-ai': '/docs/configuration/ai/',
      'cfg-ai-analyses': '/docs/configuration/ai/',
      'cfg-anomalies': '/docs/configuration/ai/',
      'cfg-langfuse': '/docs/configuration/ai/',
      'cfg-audit-log': '/docs/configuration/audit-compliance/',
      'cfg-audit-sinks': '/docs/configuration/audit-compliance/',
      'cfg-dashboard': '/docs/configuration/audit-compliance/',
      'cfg-lifecycle': '/docs/configuration/audit-compliance/',
      'compliance-reports': '/docs/configuration/audit-compliance/',
      'cfg-oauth': '/docs/configuration/auth/',
      'cfg-saml': '/docs/configuration/auth/',
      'cfg-scim': '/docs/configuration/auth/',
      'cfg-api-connectors': '/docs/configuration/connectors/',
      'cfg-connectors': '/docs/configuration/connectors/',
      'cfg-data-classifications': '/docs/configuration/datasources/',
      'cfg-datasource-health': '/docs/configuration/datasources/',
      'cfg-datasources': '/docs/configuration/datasources/',
      'cfg-drivers': '/docs/configuration/datasources/',
      'cfg-notification-channels': '/docs/configuration/notifications/',
      'cfg-slack': '/docs/configuration/notifications/',
      'cfg-smtp': '/docs/configuration/notifications/',
      'cfg-attestation': '/docs/configuration/review-workflows/',
      'cfg-deployment-pipelines': '/docs/configuration/review-workflows/',
      'cfg-review-plans': '/docs/configuration/review-workflows/',
      'cfg-review-delegation': '/docs/configuration/review-workflows/',
      'cfg-review-escalation': '/docs/configuration/review-workflows/',
      'cfg-routing-policies': '/docs/configuration/review-workflows/',
      'cfg-sql-review': '/docs/configuration/review-workflows/',
      'cfg-access-requests': '/docs/configuration/users-roles/',
      'cfg-break-glass': '/docs/configuration/users-roles/',
      'cfg-groups': '/docs/configuration/users-roles/',
      'cfg-languages': '/docs/configuration/users-roles/',
      'cfg-organizations': '/docs/configuration/users-roles/',
      'cfg-job-monitoring': '/docs/configuration/users-roles/',
      'cfg-roles': '/docs/configuration/users-roles/',
      'cfg-service-accounts': '/docs/configuration/users-roles/',
      'cfg-users': '/docs/configuration/users-roles/',
      'iac': '/docs/iac/',
      'iac-ci': '/docs/iac/',
      'iac-provider': '/docs/iac/',
      'iac-service-account': '/docs/iac/',
      'first-run': '/docs/install/',
      'run-beta': '/docs/install/',
      'run-docker-compose': '/docs/install/',
      'run-helm': '/docs/install/',
      'run-manual': '/docs/install/',
      'running': '/docs/install/',
      'end-user': '/docs/workflows/',
      'flow-diff': '/docs/workflows/',
      'flow-failure': '/docs/workflows/',
      'flow-history': '/docs/workflows/',
      'flow-mobile': '/docs/workflows/',
      'flow-request-groups': '/docs/workflows/',
      'flow-review': '/docs/workflows/',
      'flow-schedule': '/docs/workflows/',
      'flow-submit': '/docs/workflows/',
      'flow-suggestions': '/docs/workflows/',
      'flow-templates': '/docs/workflows/',
      'flow-text-to-sql': '/docs/workflows/',
  };

  function forwardLegacyDocsAnchor() {
    if (window.location.pathname !== '/docs/' && window.location.pathname !== '/docs/index.html') return;
    var hash = window.location.hash.replace(/^#/, '');
    if (!hash) return;
    var dest = LEGACY_DOCS_ANCHORS[hash];
    if (dest) window.location.replace(dest + '#' + hash);
  }

  document.addEventListener('DOMContentLoaded', function () {
    forwardLegacyDocsAnchor();
    initInstallTabs();
    initCopyButtons();
    initFlowStepper();
    initChatBubble();
    initThemeToggle();
  });
})();
