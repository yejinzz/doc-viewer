(function () {
  var base = (function () {
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      var m = (scripts[i].src || '').match(/^(https?:\/\/[^\/]+)\/docviewer\/static\/doc-viewer-client\.js/);
      if (m) return m[1];
    }
    return '';
  })();

  window.DocViewer = {
    open: function (filePath, options) {
      var url = base + '/docviewer/view?path=' + encodeURIComponent(filePath);
      window.open(url, (options && options.target) || '_blank');
    }
  };
})();
