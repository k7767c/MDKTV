// AudioWorkletGlobalScope has no Web Crypto API. Supply entropy captured on the
// main thread, without changing the Android DSP or using an insecure fallback.
addToLibrary({
  random_get: function(buffer, size) {
    if (typeof Module['randomFill'] !== 'function') throw new Error('DSP entropy source unavailable');
    Module['randomFill'](HEAPU8.subarray(buffer, buffer + size));
    return 0;
  }
});
