#include "music_dsp.h"
#include <algorithm>
#include <cmath>

MusicDsp::MusicDsp(int sampleRate,int channelCount):rate(sampleRate),channels(channelCount),fadeFrames(std::max(1,sampleRate/100)){}
double MusicDsp::Bank::sample(double input,int channel){
  if(!enabled)return input;
  double value=input*gain;
  for(int b=0;b<10;b++)if(active[b])value=state[channel][b].process1(value,filters[b],0.0);
  return std::isfinite(value)?value:0.0;
}
void MusicDsp::configure(bool enabled,const float* bands,float preamp){
  previous=current;current=Bank{};current.enabled=enabled;
  current.gain=std::pow(10.0,std::clamp(std::isfinite(preamp)?preamp:0.f,-20.f,12.f)/20.0);
  constexpr int hz[]={60,170,310,600,1000,3000,6000,12000,14000,16000};
  for(int b=0;b<10;b++){
    const double db=std::clamp(std::isfinite(bands[b])?bands[b]:0.f,-12.f,12.f);
    current.active[b]=enabled&&hz[b]<rate*0.45&&std::abs(db)>0.001;
    if(current.active[b])current.filters[b].setup(rate,hz[b],db,1.0);
  }
  remaining=fadeFrames;
}
void MusicDsp::process(int16_t* data,int samples){
  if(!data||channels<1||channels>8||samples%channels)return;
  if(!remaining&&!current.enabled)return; // bit-exact bypass, including full-scale samples
  for(int i=0;i<samples;i+=channels){
    const double blend=remaining?1.0-static_cast<double>(remaining)/fadeFrames:1.0;
    for(int c=0;c<channels;c++){
      const double input=data[i+c];
      double output=current.sample(input,c);
      if(remaining)output=previous.sample(input,c)*(1-blend)+output*blend;
      data[i+c]=static_cast<int16_t>(std::lrint(std::clamp(output,-32768.0,32767.0)));
    }
    if(remaining)--remaining;
  }
}
