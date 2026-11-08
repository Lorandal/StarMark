/* StarMark 模板编辑：画布全部在浏览器本地完成，不上传任何照片。 */
(function () {
  "use strict";

  var canvas = document.getElementById("canvas");
  if (!canvas) return;
  var ctx = canvas.getContext("2d");

  var DEFAULT_SRC = window.STARMARK_DEFAULT_PHOTO || "assets/img/mountain-opt.jpg";
  var STYLE_TITLE = { classic: "", frame: "", chip: "", film: "CLASSIC FILM · 500T" };

  var state = {
    img: null,
    style: "classic",
    model: "Xiaomi 15",
    focal: "23mm",
    aperture: "f/1.7",
    shutter: "1/60s",
    iso: "400",
    signature: "",
    size: 3.2,
    color: "#ffffff",
    position: "left",
    margin: 4,
    showDot: true,
    showParams: true,
    showModel: true,
    showSignature: false
  };

  var pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () { pending = false; render(); });
  }

  function loadImage(src) {
    return new Promise(function (resolve, reject) {
      var img = new Image();
      img.onload = function () { resolve(img); };
      img.onerror = reject;
      img.src = src;
    });
  }

  function setCanvasFor(img) {
    var maxSide = 3000;
    var w = img.naturalWidth || img.width;
    var h = img.naturalHeight || img.height;
    var scale = Math.min(1, maxSide / Math.max(w, h));
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    canvas.style.aspectRatio = canvas.width + " / " + canvas.height;
  }

  function paramLine() {
    var parts = [];
    if (state.showParams) {
      if (state.focal.trim()) parts.push(state.focal.trim());
      if (state.aperture.trim()) parts.push(state.aperture.trim());
      if (state.shutter.trim()) parts.push(state.shutter.trim());
      if (state.iso.trim()) parts.push("ISO " + state.iso.trim());
    }
    return parts.join("  ");
  }

  function modelLine() {
    return state.showModel && state.model && state.model !== "不显示机型" ? state.model : "";
  }

  function signatureLine() {
    return state.showSignature && state.signature.trim() ? state.signature.trim() : "";
  }

  function fullLine() {
    return [paramLine(), modelLine(), signatureLine()].filter(Boolean).join("   ");
  }

  function font(px, weight) {
    return (weight || 500) + " " + Math.round(px) + "px OPPOSans, MiSans, sans-serif";
  }

  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    if (ctx.roundRect) { ctx.roundRect(x, y, w, h, r); return; }
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function drawDot(x, y, r, color) {
    ctx.save();
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fillStyle = color === "#111111" ? "#111111" : "#ff6a00";
    ctx.fill();
    ctx.restore();
  }

  function withShadow(px, fn) {
    ctx.save();
    ctx.shadowColor = "rgba(0, 0, 0, 0.45)";
    ctx.shadowBlur = px * 0.5;
    ctx.shadowOffsetY = px * 0.08;
    fn();
    ctx.restore();
  }

  function alignX(position, pad, textWidth) {
    if (position === "center") return (canvas.width - textWidth) / 2;
    if (position === "right") return canvas.width - pad - textWidth;
    return pad;
  }

  /* ---------- 样式：参数条 ---------- */
  function drawClassic() {
    var w = canvas.width, h = canvas.height;
    var unit = Math.min(w, h);
    var px = unit * state.size / 100;
    var pad = unit * state.margin / 100;
    var text = fullLine();
    if (!text) return;

    ctx.font = font(px, 500);
    ctx.textBaseline = "alphabetic";
    var textW = ctx.measureText(text).width;
    var dotR = px * 0.26;
    var gap = state.showDot ? dotR * 2 + px * 0.45 : 0;
    var totalW = textW + gap;
    var x = alignX(state.position, pad, totalW);
    var y = h - pad;

    withShadow(px, function () {
      if (state.showDot) {
        drawDot(x + dotR, y - px * 0.28, dotR, state.color);
      }
      ctx.fillStyle = state.color;
      ctx.fillText(text, x + gap, y);
    });
  }

  /* ---------- 样式：白边框 ---------- */
  function drawFrame() {
    var w = canvas.width, h = canvas.height;
    var unit = Math.min(w, h);
    var border = unit * 0.045;
    var bottom = unit * 0.13;
    var innerW = w - border * 2;
    var innerH = h - border - bottom;

    var img = state.img;
    if (!img) return;
    var scale = Math.max(innerW / img.width, innerH / img.height);
    var sw = innerW / scale, sh = innerH / scale;
    var sx = (img.width - sw) / 2, sy = (img.height - sh) / 2;

    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, w, h);
    ctx.drawImage(img, sx, sy, sw, sh, border, border, innerW, innerH);

    var px = unit * state.size / 100 * 0.72;
    ctx.font = font(px, 500);
    ctx.textBaseline = "alphabetic";
    ctx.fillStyle = state.color === "#ffffff" ? "#111111" : state.color;
    var text = fullLine();
    var x = border;
    var y = h - bottom * 0.42;
    ctx.fillText(text, x, y);

    ctx.font = font(px * 0.78, 400);
    ctx.fillStyle = "rgba(17, 17, 17, 0.38)";
    var mark = "StarMark";
    ctx.fillText(mark, w - border - ctx.measureText(mark).width, h - bottom * 0.42);
  }

  /* ---------- 样式：角标 ---------- */
  function drawChip() {
    var w = canvas.width, h = canvas.height;
    var unit = Math.min(w, h);
    var px = unit * state.size / 100 * 0.86;
    var pad = unit * state.margin / 100;
    var text = fullLine();
    if (!text) return;

    ctx.font = font(px, 500);
    ctx.textBaseline = "middle";
    var textW = ctx.measureText(text).width;
    var dotR = state.showDot ? px * 0.24 : 0;
    var gap = state.showDot ? dotR * 2 + px * 0.4 : 0;
    var boxW = textW + gap + px * 1.2;
    var boxH = px * 2;
    var x = state.position === "right" ? w - pad - boxW : state.position === "center" ? (w - boxW) / 2 : pad;
    var y = h - pad - boxH;

    ctx.save();
    ctx.fillStyle = state.color === "#ffffff" ? "rgba(12, 12, 14, 0.42)" : "rgba(255, 255, 255, 0.86)";
    roundRect(x, y, boxW, boxH, boxH / 2);
    ctx.fill();
    ctx.restore();

    var cy = y + boxH / 2;
    if (state.showDot) drawDot(x + px * 0.6 + dotR, cy, dotR, state.color);
    ctx.fillStyle = state.color;
    ctx.fillText(text, x + px * 0.6 + gap, cy + px * 0.04);
  }

  /* ---------- 样式：胶片 ---------- */
  function drawFilm() {
    var w = canvas.width, h = canvas.height;
    var unit = Math.min(w, h);
    var barH = unit * 0.14;
    var px = unit * state.size / 100 * 0.78;
    var pad = unit * state.margin / 100;

    ctx.save();
    var grad = ctx.createLinearGradient(0, h - barH, 0, h);
    grad.addColorStop(0, "rgba(8, 8, 10, 0)");
    grad.addColorStop(0.35, "rgba(8, 8, 10, 0.72)");
    grad.addColorStop(1, "rgba(8, 8, 10, 0.92)");
    ctx.fillStyle = grad;
    ctx.fillRect(0, h - barH, w, barH);
    ctx.restore();

    ctx.textBaseline = "alphabetic";
    ctx.font = font(px * 0.62, 500);
    ctx.fillStyle = "rgba(255, 255, 255, 0.55)";
    var title = STYLE_TITLE.film;
    if (ctx.letterSpacing !== undefined) ctx.letterSpacing = "2px";
    ctx.fillText(title, pad, h - barH + px * 0.95);
    if (ctx.letterSpacing !== undefined) ctx.letterSpacing = "0px";

    ctx.font = font(px, 500);
    ctx.fillStyle = "#ffffff";
    var text = [paramLine(), modelLine()].filter(Boolean).join("   ");
    ctx.fillText(text, pad, h - pad * 0.9);

    ctx.font = font(px * 0.62, 400);
    ctx.fillStyle = "rgba(255, 255, 255, 0.45)";
    var sig = signatureLine();
    if (sig) {
      var tw = ctx.measureText(sig).width;
      ctx.fillText(sig, w - pad - tw, h - pad * 0.9);
    }
  }

  /* ---------- 渲染 ---------- */
  function render() {
    var img = state.img;
    if (!img) return;
    var w = canvas.width, h = canvas.height;

    ctx.clearRect(0, 0, w, h);
    if (state.style === "frame") {
      drawFrame();
      return;
    }

    var scale = Math.max(w / img.width, h / img.height);
    var sw = w / scale, sh = h / scale;
    var sx = (img.width - sw) / 2, sy = (img.height - sh) / 2;
    ctx.drawImage(img, sx, sy, sw, sh, 0, 0, w, h);

    if (state.style === "classic") drawClassic();
    else if (state.style === "chip") drawChip();
    else if (state.style === "film") drawFilm();
  }

  /* ---------- 控件 ---------- */
  function bindInput(id, key, transform) {
    var el = document.getElementById(id);
    if (!el) return;
    el.addEventListener("input", function () {
      state[key] = transform ? transform(el.value) : el.value;
      schedule();
    });
  }

  bindInput("focal", "focal");
  bindInput("aperture", "aperture");
  bindInput("shutter", "shutter");
  bindInput("iso", "iso");
  bindInput("signature", "signature");

  var model = document.getElementById("model");
  if (model) model.addEventListener("change", function () { state.model = model.value; schedule(); });

  var size = document.getElementById("size");
  var sizeVal = document.getElementById("sizeVal");
  if (size) size.addEventListener("input", function () {
    state.size = parseFloat(size.value);
    if (sizeVal) sizeVal.textContent = state.size.toFixed(1) + "%";
    schedule();
  });

  var margin = document.getElementById("margin");
  var marginVal = document.getElementById("marginVal");
  if (margin) margin.addEventListener("input", function () {
    state.margin = parseFloat(margin.value);
    if (marginVal) marginVal.textContent = state.margin.toFixed(0) + "%";
    schedule();
  });

  ["showDot", "showParams", "showModel", "showSignature"].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.addEventListener("change", function () { state[id] = el.checked; schedule(); });
  });

  var styleButtons = Array.prototype.slice.call(document.querySelectorAll("[data-style]"));
  styleButtons.forEach(function (btn) {
    btn.addEventListener("click", function () {
      state.style = btn.getAttribute("data-style");
      styleButtons.forEach(function (b) {
        var on = b === btn;
        b.classList.toggle("is-active", on);
        b.setAttribute("aria-checked", String(on));
      });
      schedule();
    });
  });

  var colorButtons = Array.prototype.slice.call(document.querySelectorAll("[data-color]"));
  colorButtons.forEach(function (btn) {
    btn.addEventListener("click", function () {
      state.color = btn.getAttribute("data-color");
      colorButtons.forEach(function (b) {
        var on = b === btn;
        b.classList.toggle("is-active", on);
        b.setAttribute("aria-checked", String(on));
      });
      schedule();
    });
  });

  var posButtons = Array.prototype.slice.call(document.querySelectorAll("[data-pos]"));
  posButtons.forEach(function (btn) {
    btn.addEventListener("click", function () {
      state.position = btn.getAttribute("data-pos");
      posButtons.forEach(function (b) { b.classList.toggle("is-active", b === btn); });
      schedule();
    });
  });

  /* ---------- 导入 / 重置 / 导出 ---------- */
  var fileInput = document.getElementById("fileInput");
  var importBtn = document.getElementById("importBtn");
  if (importBtn && fileInput) {
    importBtn.addEventListener("click", function () { fileInput.click(); });
    fileInput.addEventListener("change", function () {
      var file = fileInput.files && fileInput.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        loadImage(String(reader.result)).then(function (img) {
          state.img = img;
          setCanvasFor(img);
          render();
        });
      };
      reader.readAsDataURL(file);
      fileInput.value = "";
    });
  }

  var resetBtn = document.getElementById("resetBtn");
  if (resetBtn) {
    resetBtn.addEventListener("click", function () {
      state.style = "classic";
      state.model = "Xiaomi 15";
      state.focal = "23mm";
      state.aperture = "f/1.7";
      state.shutter = "1/60s";
      state.iso = "400";
      state.signature = "";
      state.size = 3.2;
      state.color = "#ffffff";
      state.position = "left";
      state.margin = 4;
      state.showDot = true;
      state.showParams = true;
      state.showModel = true;
      state.showSignature = false;

      document.getElementById("focal").value = "23mm";
      document.getElementById("aperture").value = "f/1.7";
      document.getElementById("shutter").value = "1/60s";
      document.getElementById("iso").value = "400";
      document.getElementById("signature").value = "";
      document.getElementById("model").value = "Xiaomi 15";
      document.getElementById("size").value = "3.2";
      document.getElementById("margin").value = "4";
      if (sizeVal) sizeVal.textContent = "3.2%";
      if (marginVal) marginVal.textContent = "4%";
      document.getElementById("showDot").checked = true;
      document.getElementById("showParams").checked = true;
      document.getElementById("showModel").checked = true;
      document.getElementById("showSignature").checked = false;

      styleButtons.forEach(function (b) { b.classList.toggle("is-active", b.getAttribute("data-style") === "classic"); b.setAttribute("aria-checked", String(b.getAttribute("data-style") === "classic")); });
      colorButtons.forEach(function (b) { b.classList.toggle("is-active", b.getAttribute("data-color") === "#ffffff"); b.setAttribute("aria-checked", String(b.getAttribute("data-color") === "#ffffff")); });
      posButtons.forEach(function (b) { b.classList.toggle("is-active", b.getAttribute("data-pos") === "left"); });

      loadImage(DEFAULT_SRC).then(function (img) {
        state.img = img;
        setCanvasFor(img);
        render();
      });
    });
  }

  var exportBtn = document.getElementById("exportBtn");
  if (exportBtn) {
    exportBtn.addEventListener("click", function () {
      if (!state.img) return;
      canvas.toBlob(function (blob) {
        if (!blob) return;
        var url = URL.createObjectURL(blob);
        var a = document.createElement("a");
        a.href = url;
        a.download = "starmark-watermark.png";
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
      }, "image/png");
    });
  }

  /* ---------- 启动 ---------- */
  function start() {
    loadImage(DEFAULT_SRC).then(function (img) {
      state.img = img;
      setCanvasFor(img);
      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(render);
      }
      render();
    }).catch(function () {
      ctx.fillStyle = "#eee";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
    });
  }

  start();
})();
