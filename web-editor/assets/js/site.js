/* 共享交互：导航状态、滚动入场、年份。 */
(function () {
  "use strict";

  /* 当前页高亮（防止静态复制页面时遗漏） */
  var file = (location.pathname.split("/").pop() || "index.html").toLowerCase();
  if (!file) file = "index.html";
  document.querySelectorAll(".nav-links a").forEach(function (a) {
    var href = (a.getAttribute("href") || "").toLowerCase();
    if (href === file) {
      a.classList.add("is-active");
      a.setAttribute("aria-current", "page");
    }
  });

  /* 滚动入场：只在支持且未开启减弱动态时启用 */
  if (!("IntersectionObserver" in window)) return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  var targets = document.querySelectorAll(".capability, .screen, .spec-row, .masthead > div, .masthead-photo, .section-head, .band-inner, .sheet-row, .brand-copy > *");
  if (!targets.length) return;

  targets.forEach(function (el) {
    el.style.opacity = "0";
    el.style.transform = "translateY(14px)";
    el.style.transition = "opacity .6s cubic-bezier(.22,.8,.24,1), transform .6s cubic-bezier(.22,.8,.24,1)";
  });

  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      var el = entry.target;
      el.style.opacity = "1";
      el.style.transform = "none";
      io.unobserve(el);
    });
  }, { rootMargin: "0px 0px -8% 0px", threshold: 0.05 });

  targets.forEach(function (el, i) {
    el.style.transitionDelay = Math.min(i % 4, 3) * 40 + "ms";
    io.observe(el);
  });
})();
