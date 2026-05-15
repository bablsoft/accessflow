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
      // Browser <picture><source media="(prefers-color-scheme: light)"> doesn't
      // re-evaluate when JS toggles data-theme — swap the <img src> directly.
      var pics = document.querySelectorAll(
        'picture > img[src*="-dark.png"], picture > img[src*="-light.png"]'
      );
      pics.forEach(function (img) {
        var want  = theme === 'light' ? '-light.png' : '-dark.png';
        var other = theme === 'light' ? '-dark.png'  : '-light.png';
        if (img.src.indexOf(other) !== -1) {
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

  document.addEventListener('DOMContentLoaded', function () {
    initInstallTabs();
    initCopyButtons();
    initFlowStepper();
    initThemeToggle();
  });
})();
