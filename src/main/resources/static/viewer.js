(function () {
  'use strict';

  var cfg = window.DOCVIEWER_CONFIG;
  var $ = function (id) { return document.getElementById(id); };

  $('filename').textContent = cfg.filename || '문서 뷰어';
  $('download-link').href = cfg.fileUrl;
  $('download-link').setAttribute('download', cfg.filename || 'document');
  $('print-btn').addEventListener('click', function () { window.print(); });

  function showError(msg, showDownload) {
    $('loading').classList.add('hidden');
    $('error-message').textContent = msg;
    if (showDownload && cfg.downloadKey) {
      $('error-download').innerHTML =
        '<a href="/docviewer/file?key=' + encodeURIComponent(cfg.downloadKey) + '">원본 파일 다운로드</a>';
    }
    $('error').style.display = 'block';
  }

  function showLoading(text) {
    $('loading-text').textContent = text || '문서를 불러오는 중입니다...';
    $('loading').classList.remove('hidden');
  }

  // IMAGE
  if (cfg.renderType === 'image') {
    $('loading').classList.add('hidden');
    var img = $('image-content');
    img.onerror = function () { showError('이미지를 불러올 수 없습니다.', true); };
    img.src = cfg.fileUrl;
    img.classList.remove('hidden');
    return;
  }

  // TEXT
  if (cfg.renderType === 'text') {
    showLoading('텍스트 파일을 불러오는 중...');
    fetch(cfg.fileUrl)
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
      })
      .then(function (buf) {
        var text;
        try { text = new TextDecoder('euc-kr').decode(buf); }
        catch (e) { text = new TextDecoder('utf-8').decode(buf); }
        $('loading').classList.add('hidden');
        $('text-content').textContent = text;
        $('text-content').classList.remove('hidden');
      })
      .catch(function (e) { showError('파일을 불러올 수 없습니다: ' + e.message, true); });
    return;
  }

  // HWP / HWPX
  if (cfg.renderType === 'hwp') {
    showLoading('HWP 문서를 불러오는 중...');
    fetch(cfg.fileUrl)
      .then(function(r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
      })
      .then(function(buf) {
        // measureTextWidth 등록 (rhwp 필수 설정 — WASM init 전에 호출)
        if (!globalThis.measureTextWidth) {
          var _ctx = null;
          var _lastFont = '';
          globalThis.measureTextWidth = function(font, text) {
            if (!_ctx) _ctx = document.createElement('canvas').getContext('2d');
            if (font !== _lastFont) { _ctx.font = font; _lastFont = font; }
            return _ctx.measureText(text).width;
          };
        }
        return import('/docviewer/static/lib/rhwp/rhwp.js').then(function(mod) {
          return mod.default({ module_or_path: '/docviewer/static/lib/rhwp/rhwp_bg.wasm' })
            .then(function() { return mod; });
        }).then(function(mod) {
          var doc = new mod.HwpDocument(new Uint8Array(buf));
          var total = doc.pageCount();
          var current = 0;
          var container = $('hwp-container');

          $('loading').classList.add('hidden');
          container.classList.remove('hidden');
          $('pdf-controls').classList.remove('hidden');
          $('zoom-in').style.display = 'none';
          $('zoom-out').style.display = 'none';
          $('zoom-level').style.display = 'none';
          $('page-info').textContent = '1 / ' + total;

          function renderPage(idx) {
            container.innerHTML = doc.renderPageSvg(idx);
            $('page-info').textContent = (idx + 1) + ' / ' + total;
          }
          renderPage(0);

          $('prev-page').addEventListener('click', function() {
            if (current <= 0) return;
            current--; renderPage(current);
          });
          $('next-page').addEventListener('click', function() {
            if (current >= total - 1) return;
            current++; renderPage(current);
          });
        });
      })
      .catch(function(e) { showError('HWP 문서를 열 수 없습니다: ' + e.message, true); });
    return;
  }

  // PDF
  var pdfjsLib = window['pdfjs-dist/build/pdf'];
  if (!pdfjsLib) { showError('PDF 뷰어를 초기화할 수 없습니다. 페이지를 새로고침하세요.', false); return; }
  pdfjsLib.GlobalWorkerOptions.workerSrc = '/docviewer/static/lib/pdf.worker.min.js';

  var pdfDoc = null;
  var currentPage = 1;
  var scale = 1.0;
  var canvas = $('pdf-canvas');
  var ctx = canvas.getContext('2d');
  var rendering = false;

  showLoading('문서를 변환하는 중입니다...');

  function containerWidth() { return $('viewer-container').clientWidth - 40; }

  function renderPage(num) {
    if (rendering) return Promise.resolve();
    rendering = true;
    return pdfDoc.getPage(num).then(function (page) {
      var base = page.getViewport({ scale: 1 });
      var autoScale = Math.min(containerWidth() / base.width, 2.0);
      var vp = page.getViewport({ scale: autoScale * scale });
      canvas.width = vp.width;
      canvas.height = vp.height;
      return page.render({ canvasContext: ctx, viewport: vp }).promise;
    }).then(function () {
      $('page-info').textContent = currentPage + ' / ' + pdfDoc.numPages;
      rendering = false;
    }).catch(function (e) {
      rendering = false;
      showError('페이지를 렌더링할 수 없습니다: ' + e.message, true);
    });
  }

  pdfjsLib.getDocument(cfg.fileUrl).promise.then(function (doc) {
    pdfDoc = doc;
    $('loading').classList.add('hidden');
    canvas.classList.remove('hidden');
    $('pdf-controls').classList.remove('hidden');
    return renderPage(1);
  }).catch(function (e) {
    showError('PDF를 열 수 없습니다: ' + e.message, true);
  });

  $('prev-page').addEventListener('click', function () {
    if (currentPage <= 1) return;
    currentPage--; renderPage(currentPage);
  });
  $('next-page').addEventListener('click', function () {
    if (!pdfDoc || currentPage >= pdfDoc.numPages) return;
    currentPage++; renderPage(currentPage);
  });
  $('zoom-in').addEventListener('click', function () {
    scale = Math.min(scale + 0.25, 3.0);
    $('zoom-level').textContent = Math.round(scale * 100) + '%';
    renderPage(currentPage);
  });
  $('zoom-out').addEventListener('click', function () {
    scale = Math.max(scale - 0.25, 0.5);
    $('zoom-level').textContent = Math.round(scale * 100) + '%';
    renderPage(currentPage);
  });
  window.addEventListener('resize', function () { if (pdfDoc) renderPage(currentPage); });
})();
