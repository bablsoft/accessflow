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

  document.addEventListener('DOMContentLoaded', function () {
    initInstallTabs();
    initCopyButtons();
    initFlowStepper();
  });
})();
