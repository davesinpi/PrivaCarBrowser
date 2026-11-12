(function () {
  if (window.__privacar_injected) return;
  window.__privacar_injected = true;

  /* ---------- 1. Web Speech API polyfill (no keyboard while driving) ----------
     Bridges to the native SpeechRecognizer over the "_SpeechBridgeChannel"
     WebMessageListener. The page calls SpeechRecognition like normal. */
  try {
    var ch = window._SpeechBridgeChannel; // injected by addWebMessageListener
    if (ch && !window.webkitSpeechRecognition) {
      var current = null;
      function SR() {
        this.lang = "en-US";
        this.continuous = false;
        this.interimResults = false;
        this.onresult = this.onerror = this.onend = this.onstart = null;
      }
      SR.prototype.start = function () {
        current = this;
        try { ch.postMessage(JSON.stringify({ type: "start", lang: this.lang })); } catch (e) {}
        if (this.onstart) this.onstart({});
      };
      SR.prototype.stop = function () {
        try { ch.postMessage(JSON.stringify({ type: "stop" })); } catch (e) {}
      };
      SR.prototype.abort = SR.prototype.stop;
      window.SpeechRecognition = SR;
      window.webkitSpeechRecognition = SR;

      window.__sr_event = function (kind, json) {
        if (!current) return;
        var data = {};
        try { data = JSON.parse(json); } catch (e) {}
        if (kind === "result" && current.onresult) {
          var alts = (data.a || []).map(function (t) {
            return { transcript: t, confidence: 0.9 };
          });
          alts.isFinal = !!data.f;
          var ev = { results: [alts], resultIndex: 0 };
          ev.results.length = 1;
          current.onresult(ev);
        } else if (kind === "error" && current.onerror) {
          current.onerror({ error: data.error || "aborted" });
        } else if (kind === "end") {
          if (current.onend) current.onend({});
          current = null;
        }
      };
    }
  } catch (e) {}

  /* ---------- 2. Widevine robustness downgrade (play DRM video on head units) ----
     Head units rarely have HW-secure Widevine. Rewrite HW_SECURE_* to
     SW_SECURE_DECODE (L3). YouTube is excluded by the host check below. */
  try {
    if (!/(^|\.)youtube\.com$/.test(location.hostname) &&
        navigator.requestMediaKeySystemAccess) {
      var orig = navigator.requestMediaKeySystemAccess.bind(navigator);
      function downgrade(cfgs) {
        return (cfgs || []).map(function (c) {
          var d = Object.assign({}, c);
          ["audioCapabilities", "videoCapabilities"].forEach(function (k) {
            if (Array.isArray(d[k])) {
              d[k] = d[k].map(function (cap) {
                var nc = Object.assign({}, cap);
                if (nc.robustness && /HW_SECURE/.test(nc.robustness)) {
                  nc.robustness = "SW_SECURE_DECODE";
                }
                return nc;
              });
            }
          });
          return d;
        });
      }
      var patched = function (keySystem, cfgs) {
        return orig(keySystem, downgrade(cfgs)).catch(function () {
          return orig(keySystem, cfgs); // fall back to the original request
        });
      };
      // disguise as native code
      patched.toString = function () { return "function requestMediaKeySystemAccess() { [native code] }"; };
      navigator.requestMediaKeySystemAccess = patched;
    }
  } catch (e) {}

  /* ---------- 3. Privacy: advertise Do-Not-Track / no global privacy signal ---- */
  try {
    Object.defineProperty(navigator, "doNotTrack", { get: function () { return "1"; } });
    Object.defineProperty(navigator, "globalPrivacyControl", { get: function () { return true; } });
  } catch (e) {}
})();
