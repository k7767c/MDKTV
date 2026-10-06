#pragma once
#include <array>
#include <cstdint>
#include <memory>
// Parameter ABI matches VoiceFxSettings.parameters. No allocation in process().
class VocalFx {
public:
 static constexpr int count=18;
 explicit VocalFx(int sampleRate);
 ~VocalFx();
 bool configure(bool enabled,const float* values);
 void process(const int16_t* input,int frames,int channels,int16_t* stereo);
 int latency() const;
private:
 struct Impl;
 std::unique_ptr<Impl> p;
};
