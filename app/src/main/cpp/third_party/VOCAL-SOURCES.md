# Vocal DSP dependencies

Vendored upstream source files are unchanged; only the subset needed for this app is included.

| Component | Revision | Used files | License |
| --- | --- | --- | --- |
| [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) | `57b93f4e9206a089a45387eaa39bdc9f310d3308` | `signalsmith/signalsmith-stretch.h` | MIT, `signalsmith/LICENSE.txt` |
| [Signalsmith Linear](https://github.com/Signalsmith-Audio/linear) | `5668673560146a9cfe38c25315071e3fd68c8317` (0.3.1) | `signalsmith-linear/*.h` | MIT, `signalsmith-linear/LICENSE.txt` |
| [STK](https://github.com/thestk/stk) | `6aacd357d76250bb7da2b1ddf675651828784bbc` | `stk/include/{Stk,Filter,Effect,Delay,NRev}.h`, `stk/src/{Stk,Delay,NRev}.cpp` | STK permissive license, `stk/LICENSE` |

The APK includes all three notices under `assets/licenses/`. STK NRev needs no raw waveform assets. No animal sounds, proprietary preset banks or model weights are bundled. These dependency licenses do not replace the application's existing non-commercial license.

`vocal_fx.cpp` integrates Stretch pitch/formant processing and NRev reverberation. The surrounding EQ, compression, echo, chorus, limiting and scale quantization are application code, not attributed to either upstream library. The 120 ms Stretch window follows the upstream default duration: shorter windows failed the small pitch-correction regression.
