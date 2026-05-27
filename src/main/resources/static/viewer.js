(function () {
  'use strict';

  var cfg = window.DOCVIEWER_CONFIG;
  var $ = function (id) { return document.getElementById(id); };

  $('filename').textContent = cfg.filename;
  $('download-link').href = cfg.fileUrl;
  $('download-link').setAttribute('download', cfg.filename);
  $('print-btn').addEventListener('click', function () { window.print(); });

  function showError(msg) {
    $('loading').classList.add('hidden');
    $('error').textContent = msg;
    $('error').classList.remove('hidden');
  }

  // IMAGE
  if (cfg.renderType === 'image') {
    $('loading').classList.add('hidden');
    $('image-content').src = cfg.fileUrl;
    $('image-content').classList.remove('hidden');
    return;
  }

  // TEXT
  if (cfg.renderType === 'text') {
    fetch(cfg.fileUrl)
      .then(function (r) { return r.arrayBuffer(); })
      .then(function (buf) {
        var text;
        try { text = new TextDecoder('euc-kr').decode(buf); }
        catch (e) { text = new TextDecoder('utf-8').decode(buf); }
        $('loading').classList.add('hidden');
        $('text-content').textContent = text;
        $('text-content').classList.remove('hidden');
      })
      .catch(function (e) { showError('파일을 불러올 수 없습니다: ' + e.message); });
    return;
  }

  // PDF (cfg.renderType === 'pdf')
  var pdfjsLib = window['pdfjs-dist/build/pdf'];
  if (!pdfjsLib) { showError('pdf.js 로드 실패'); return; }
  pdfjsLib.GlobalWorkerOptions.workerSrc = '/docviewer/static/lib/pdf.worker.min.js';

  var pdfDoc = null;
  var currentPage = 1;
  var scale = 1.0;
  var canvas = $('pdf-canvas');
  var ctx = canvas.getContext('2d');
  var rendering = false;

  function containerWidth() {
    return $('viewer-container').clientWidth - 40;
  }

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
      $('page-info').textContent = num + ' / ' + pdfDoc.numPages;
      rendering = false;
    });
  }

  pdfjsLib.getDocument(cfg.fileUrl).promise.then(function (doc) {
    pdfDoc = doc;
    $('loading').classList.add('hidden');
    canvas.classList.remove('hidden');
    $('pdf-controls').classList.remove('hidden');
    return renderPage(1);
  }).catch(function (e) {
    showError('PDF를 렌더링할 수 없습니다: ' + e.message);
  });

  $('prev-page').addEventListener('click', function () {
    if (currentPage <= 1) return;
    currentPage--;
    renderPage(currentPage);
  });
  $('next-page').addEventListener('click', function () {
    if (!pdfDoc || currentPage >= pdfDoc.numPages) return;
    currentPage++;
    renderPage(currentPage);
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

  window.addEventListener('resize', function () {
    if (pdfDoc) renderPage(currentPage);
  });
})();
