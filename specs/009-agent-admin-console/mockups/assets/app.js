/* OryxOS Admin Console — Mockup Interactivity
 * 半静态交互 per spec FR-021
 *   - 折叠：点击 .timeline-card-header 切换 .expanded
 *   - 复制：点击 .copy-btn 复制最近 <code> / .kv-val 到剪贴板
 *   - Tabs：点击 .tab 切换 .active
 *   - 自动刷新：演示用，无真实数据
 */

(function() {
  'use strict';

  // Timeline card collapse/expand
  document.querySelectorAll('.timeline-card-header').forEach(function(header) {
    header.addEventListener('click', function() {
      const card = header.closest('.timeline-card');
      if (card) card.classList.toggle('expanded');
    });
  });

  // Copy buttons
  document.querySelectorAll('.copy-btn').forEach(function(btn) {
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      const target = btn.getAttribute('data-copy-target');
      const el = target ? document.querySelector(target) : btn.parentElement.querySelector('code, .kv-val');
      if (!el) return;
      const text = el.textContent.trim();
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(function() {
          const orig = btn.textContent;
          btn.textContent = '✓ 已复制';
          setTimeout(function() { btn.textContent = orig; }, 1200);
        });
      }
    });
  });

  // Tabs
  document.querySelectorAll('[data-tabs]').forEach(function(tabsContainer) {
    const tabs = tabsContainer.querySelectorAll('.tab');
    const panelsContainer = document.querySelector(tabsContainer.getAttribute('data-tabs'));
    if (!panelsContainer) return;
    const panels = panelsContainer.querySelectorAll('[data-tab-panel]');
    tabs.forEach(function(tab) {
      tab.addEventListener('click', function() {
        const target = tab.getAttribute('data-tab');
        tabs.forEach(function(t) { t.classList.remove('active'); });
        tab.classList.add('active');
        panels.forEach(function(p) {
          if (p.getAttribute('data-tab-panel') === target) {
            p.style.display = '';
          } else {
            p.style.display = 'none';
          }
        });
      });
    });
  });

  // Tag input click filter (visual only)
  document.querySelectorAll('.filter-tag').forEach(function(tag) {
    tag.addEventListener('click', function() {
      tag.classList.toggle('active');
    });
  });

  // Search input (visual only, no real filtering)
  document.querySelectorAll('.search-input').forEach(function(input) {
    input.addEventListener('focus', function() {
      input.style.borderColor = 'var(--color-accent)';
    });
    input.addEventListener('blur', function() {
      input.style.borderColor = '';
    });
  });

  // Mark all rows with click → tooltip (visual)
  document.querySelectorAll('[data-tooltip]').forEach(function(el) {
    // passive: tooltip via CSS hover
  });
})();
