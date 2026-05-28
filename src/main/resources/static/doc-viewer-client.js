(function () {
  var base = (function () {
    var scripts = document.getElementsByTagName('script');
    for (var i = 0; i < scripts.length; i++) {
      var m = (scripts[i].src || '').match(
        /^(https?:\/\/[^\/]+)\/docviewer\/static\/doc-viewer-client\.js/);
      if (m) return m[1];
    }
    return '';
  })();

  window.DocViewer = {
    // key: CMS의 ATCH_FILE_ID + "_" + FILE_SN (예: "FILE_000000000080Gi9_0")
    open: function (key, options) {
      var url = base + '/docviewer/view?key=' + encodeURIComponent(key);
      window.open(url, (options && options.target) || '_blank');
    }
  };
})();
