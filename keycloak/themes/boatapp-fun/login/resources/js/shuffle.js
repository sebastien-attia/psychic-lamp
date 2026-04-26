/*
 * Boat App login splash — random diagonal-split shuffle.
 *
 * On `DOMContentLoaded` we pick a fresh split (two distinct brand
 * colours, a random angle in [30°,150°], a random split position in
 * [30%,70%]) and write the four CSS custom properties consumed by
 * `.boatapp-split-bg`. Clicking the floating Shuffle button re-rolls
 * without reload.
 *
 * No frameworks: ships as a single self-executing function so the
 * Keycloak base theme's <script src="..."> tag is the only wiring.
 */
(function () {
  var COLORS = ['#0038B8', '#B82200', '#94B800'];

  function rand(min, max) {
    return min + Math.random() * (max - min);
  }

  /**
   * Pick two distinct colours from the brand palette without
   * replacement. Uses the standard "pick i, then j ≥ i bumped by one
   * if it landed on i" trick to keep the result uniform across the
   * 3 × 2 = 6 ordered pairs without rejection sampling.
   */
  function pickPair() {
    var i = Math.floor(Math.random() * COLORS.length);
    var j = Math.floor(Math.random() * (COLORS.length - 1));
    if (j >= i) j += 1;
    return [COLORS[i], COLORS[j]];
  }

  /**
   * Compute a fresh split and write it to the CSS custom properties
   * on `:root`. Pure DOM-side state — no localStorage, no cookies —
   * so reloading also re-rolls.
   */
  function applySplit() {
    var pair = pickPair();
    var root = document.documentElement.style;
    root.setProperty('--split-c1', pair[0]);
    root.setProperty('--split-c2', pair[1]);
    root.setProperty('--split-angle', rand(30, 150).toFixed(1) + 'deg');
    root.setProperty('--split-pos', rand(30, 70).toFixed(1) + '%');
  }

  // The script tag is `defer`-loaded by `template.ftl`, which means
  // `defer` scripts execute after the document has been parsed but
  // before `DOMContentLoaded`. Running synchronously avoids the
  // single-frame flash of the default Bleu/Olive split that a
  // `DOMContentLoaded` handler would introduce.
  applySplit();
  var btn = document.getElementById('shuffle-btn');
  if (btn) btn.addEventListener('click', applySplit);
})();
