#pragma once
#include <array>
#include <cstdint>
#include "DspFilters/RBJ.h"
#include "DspFilters/State.h"

class MusicDsp {
  struct Bank {
    bool enabled=false;
    double gain=1;
    std::array<Dsp::RBJ::BandShelf,10> filters;
    std::array<bool,10> active{};
    std::array<std::array<Dsp::DirectFormII,10>,8> state;
    double sample(double input,int channel);
  } current,previous;
  int rate,channels,remaining=0,fadeFrames;
public:
  MusicDsp(int sampleRate,int channelCount);
  void configure(bool enabled,const float* bands,float preamp);
  void process(int16_t* data,int samples);
};
