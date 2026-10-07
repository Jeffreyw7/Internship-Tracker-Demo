// Runs before the app bundle: only a non-sensitive appearance value is cached.
(function () {
  var preference = 'system';
  try {
    var saved = localStorage.getItem('campus.theme.bootstrap.v1');
    if (saved === 'dark' || saved === 'light') preference = saved;
  } catch (_) { /* Storage can be disabled. */ }
  var darkOS = false;
  try { darkOS = !!window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches; } catch (_) {}
  var resolved = preference === 'system' ? (darkOS ? 'dark' : 'light') : preference;
  document.documentElement.dataset.theme = resolved;
  document.documentElement.dataset.themePreference = preference;
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', resolved === 'dark' ? '#101815' : '#f8faf9');
}());
