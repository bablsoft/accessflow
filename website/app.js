// AccessFlow public site — vanilla JS for tab switching, copy button, and how-it-works stepper.

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

  function initThemeToggle() {
    var STORAGE_KEY = 'accessflow.theme';
    var root = document.documentElement;
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    if (!buttons.length) return;

    var mql = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;

    function currentTheme() {
      var attr = root.getAttribute('data-theme');
      if (attr === 'light' || attr === 'dark') return attr;
      return mql && mql.matches ? 'light' : 'dark';
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
      var t = currentTheme();
      var goingTo = t === 'light' ? 'dark' : 'light';
      buttons.forEach(function (btn) {
        btn.setAttribute('aria-pressed', t === 'light' ? 'true' : 'false');
        btn.setAttribute('aria-label', 'Switch to ' + goingTo + ' theme');
      });
      swapDocsImages(t);
    }

    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var next = currentTheme() === 'light' ? 'dark' : 'light';
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
      'cfg-access-requests': '/docs/configuration/users-roles/',
      'cfg-break-glass': '/docs/configuration/users-roles/',
      'cfg-groups': '/docs/configuration/users-roles/',
      'cfg-languages': '/docs/configuration/users-roles/',
      'cfg-organizations': '/docs/configuration/users-roles/',
      'cfg-roles': '/docs/configuration/users-roles/',
      'cfg-users': '/docs/configuration/users-roles/',
      'iac': '/docs/iac/',
      'iac-deployment-gate': '/docs/iac/',
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
    initThemeToggle();
  });
})();
