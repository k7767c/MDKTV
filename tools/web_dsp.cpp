#include "vocal_fx.h"
#include "music_dsp.h"
#include "signalsmith/signalsmith-stretch.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <emscripten/emscripten.h>

// The original Android DSP implementations remain the source of truth.
// The browser adapter changes the host ABI, not the vocal effect algorithms.
struct WebMusic {
  MusicDsp eq;
  signalsmith::stretch::SignalsmithStretch<float> stretch{1};
  int channels;
  float pitch = 0;
  std::array<std::array<float, 256>, 2> in{}, out{};
  WebMusic(int rate, int channels):eq(rate, channels),channels(channels) {
    stretch.configure(channels, int(rate * .06), int(rate * .015), true);
  }
};

extern "C" {
EMSCRIPTEN_KEEPALIVE VocalFx* fx_create(int rate) {
  return rate >= 8000 && rate <= 192000 ? new VocalFx(rate) : nullptr;
}
EMSCRIPTEN_KEEPALIVE int fx_config(VocalFx* fx, int enabled, const float* values) {
  return fx && values && fx->configure(enabled != 0, values);
}
EMSCRIPTEN_KEEPALIVE void fx_process(VocalFx* fx, const int16_t* input, int frames, int channels, int16_t* output) {
  if(fx && input && output && frames > 0 && frames <= 256 && channels > 0 && channels <= 2)
    fx->process(input, frames, channels, output);
}
EMSCRIPTEN_KEEPALIVE int fx_latency(VocalFx* fx) { return fx ? fx->latency() : 0; }
EMSCRIPTEN_KEEPALIVE void fx_destroy(VocalFx* fx) { delete fx; }
EMSCRIPTEN_KEEPALIVE WebMusic* music_create(int rate, int channels) {
  return rate >= 8000 && rate <= 192000 && channels >= 1 && channels <= 2 ? new WebMusic(rate,channels) : nullptr;
}
EMSCRIPTEN_KEEPALIVE void music_config(WebMusic* music, int enabled, const float* bands, float preamp) {
  if (!music || !bands || !std::isfinite(preamp) || preamp < -24 || preamp > 24) return;
  for(int i = 0; i < 10; ++i) if(!std::isfinite(bands[i]) || bands[i] < -12 || bands[i] > 12) return;
  music->eq.configure(enabled != 0, bands, preamp);
}
EMSCRIPTEN_KEEPALIVE void music_pitch(WebMusic* music, float semitones) {
  if(!music || !std::isfinite(semitones) || semitones < -12 || semitones > 12) return;
  if(music->pitch != semitones) music->stretch.reset();
  music->pitch = semitones;
  music->stretch.setTransposeSemitones(semitones);
}
EMSCRIPTEN_KEEPALIVE int music_latency(WebMusic* music) {
  return music && music->pitch != 0 ? music->stretch.inputLatency() + music->stretch.outputLatency() : 0;
}
EMSCRIPTEN_KEEPALIVE void music_process(WebMusic* music, int16_t* data, int samples) {
  if(!music || !data || samples < 1 || samples > 512 || samples % music->channels != 0) return;
  music->eq.process(data, samples);
  if(music->pitch == 0) return;
  const int frames = samples / music->channels;
  const float* inputs[] = {music->in[0].data(), music->in[1].data()};
  float* outputs[] = {music->out[0].data(), music->out[1].data()};
  for(int i = 0; i < frames; ++i)
    for(int c = 0; c < music->channels; ++c) music->in[c][i] = data[i * music->channels + c] / 32768.f;
  music->stretch.process(inputs, frames, outputs, frames);
  for(int i = 0; i < frames; ++i)
    for(int c = 0; c < music->channels; ++c) {
      float value = music->out[c][i];
      data[i * music->channels + c] = std::isfinite(value) ? int16_t(std::clamp(value, -.95f, .95f) * 32767) : 0;
    }
}
EMSCRIPTEN_KEEPALIVE void music_destroy(WebMusic* music) { delete music; }
}
