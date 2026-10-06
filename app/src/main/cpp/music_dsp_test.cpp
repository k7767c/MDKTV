#include "music_dsp.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>
static std::vector<int16_t> tone(int rate,double hz,int channels=2){
  std::vector<int16_t> data(rate*channels);
  for(int i=0;i<rate;i++)data[i*channels]=static_cast<int16_t>(4000*std::sin(6.283185307179586*hz*i/rate));
  return data;
}
static double rms(const std::vector<int16_t>& data,int channels=2){
  double energy=0;int count=0;
  for(size_t i=data.size()/2;i<data.size();i+=channels){energy+=double(data[i])*data[i];count++;}
  return std::sqrt(energy/count);
}
int main(){
  float flat[10]={};float notch[10]={};notch[4]=-12;
  MusicDsp eq(48000,2);eq.configure(true,notch,0);
  auto data=tone(48000,1000);const double original=rms(data);eq.process(data.data(),data.size());
  double ratio=rms(data)/original;assert(ratio>0.23&&ratio<0.27);
  for(size_t i=1;i<data.size();i+=2)assert(data[i]==0);
  eq.configure(true,flat,-6);data=tone(48000,1000);eq.process(data.data(),data.size());
  ratio=rms(data)/original;assert(ratio>.49&&ratio<.52);
  eq.configure(false,flat,0);data.assign(2000,0);eq.process(data.data(),data.size());
  data={-32768,32767,-1,1,500,-500};auto copy=data;eq.process(data.data(),data.size());assert(data==copy);
  float extreme[10];for(auto& v:extreme)v=12;extreme[9]=std::numeric_limits<float>::quiet_NaN();
  for(int rate:{8000,44100,48000,96000}){
    MusicDsp processor(rate,2);processor.configure(true,extreme,12);data=tone(rate,400);
    processor.process(data.data(),data.size());assert(rms(data)>0);
    for(size_t i=1;i<data.size();i+=2)assert(data[i]==0);
  }
  MusicDsp fast(48000,2);fast.configure(true,extreme,-12);
  std::vector<int16_t> block(960,1000);
  auto start=std::chrono::steady_clock::now();
  for(int i=0;i<2000;i++)fast.process(block.data(),block.size());
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  std::printf("PASS: -12dB band response, -6dB preamp, stereo isolation, exact bypass, rates and bounded output.\n2000 x 10ms audio: %.1f ms CPU, %.3f ms/block\n",ms,ms/2000);
  assert(ms<20000);
}
