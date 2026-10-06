#include "vocal_fx.h"
#include <array>
#include <vector>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <algorithm>
void check(bool v,const char* message){if(!v){fprintf(stderr,"FAIL %s\n",message);std::exit(1);}}
std::array<float,18> defaults(){return {0,0,0,1.2f,0,160,0,50,0,0,0,0,0,0,0,0,0,0};}
double power(const std::vector<int16_t>& data,float hz,int rate){
 double a=0,b=0;for(size_t i=rate/2;i<data.size()/2;i+=4){double ph=6.28318530718*hz*i/rate;a+=data[i*2]*std::cos(ph);b+=data[i*2]*std::sin(ph);}return a*a+b*b;
}
std::vector<int16_t> run(VocalFx& fx,int rate,float hz,int seconds=2){
 std::vector<int16_t> out(rate*seconds*2);std::array<int16_t,480> input{};
 for(int offset=0;offset<rate*seconds;offset+=480){int count=std::min(480,rate*seconds-offset);
  for(int i=0;i<count;i++)input[i]=int16_t(8000*std::sin(6.28318530718*hz*(offset+i)/rate));
  auto saved=input;fx.process(input.data(),count,1,out.data()+offset*2);check(input==saved,"raw scoring input unchanged");
 }return out;
}
int main(){
 for(int rate:{8000,16000,48000}){
  VocalFx fx(rate);auto v=defaults();std::array<int16_t,480> input{};std::array<int16_t,960> output{};
  for(int i=0;i<480;i++)input[i]=i*20-4000;auto before=input;
  fx.process(input.data(),480,1,output.data());
  for(int i=0;i<480;i++)check(output[i*2]==input[i]&&output[i*2+1]==input[i],"disabled exact bypass");
  v[0]=7;check(fx.configure(true,v.data()),"pitch config");auto shifted=run(fx,rate,220);
  double target=power(shifted,220*std::pow(2.,7./12),rate),original=power(shifted,220,rate);
  printf("rate=%d pitch_target_over_original=%.2f latency_ms=%.2f\n",rate,target/std::max(1.,original),1000.f*fx.latency()/rate);
  check(target>original*10,"pitch shift spectral target");
  check(fx.latency()<=rate*61/1000,"fixed voice shift stays within 61 ms");
  // TV monitoring always resamples USB audio to 48 kHz before this engine.
  if(rate==48000)for(float source:{90.f,130.f,220.f,440.f})for(float semitones:{-12.f,-10.f,-8.f,-7.f,3.f,7.f,10.f,12.f}){
   fx.configure(false,defaults().data());auto voice=defaults();voice[0]=semitones;
   voice[1]=semitones<0?-7.f:(semitones>=5?8.f:4.f);
   check(fx.configure(true,voice.data()),"voice range config");
   check(fx.latency()<=rate*(std::abs(semitones)>=7?41:61)/1000,"adaptive voice latency budget");
   auto signal=run(fx,rate,source);float expected=source*std::pow(2.f,semitones/12);
   double desired=power(signal,expected,rate),dry=power(signal,source,rate);
   printf("voice rate=%d input=%.0f shift=%.0f target_over_dry=%.2f\n",rate,source,semitones,desired/std::max(1.,dry));
   check(desired>dry*10,"low latency voice range rejects dry pitch");
  }
  fx.configure(false,defaults().data());v=defaults();v[12]=100;check(fx.configure(true,v.data()),"tune config");auto tuned=run(fx,rate,225);
  printf("tuneRatio=%.3f\n",power(tuned,220,rate)/power(tuned,225,rate));check(power(tuned,220,rate)>power(tuned,225,rate)*3,"chromatic correction");
  float peakHz=0;double best=0;for(float hz=215;hz<=230;hz+=.25f){double p=power(tuned,hz,rate);if(p>best){best=p;peakHz=hz;}}
  printf("tuned_peak_hz=%.2f\n",peakHz);check(std::abs(peakHz-220)<=1,"tune accurate within 1 Hz");
  v=defaults();v[2]=60;v[3]=3;v[7]=100;fx.configure(true,v.data());input.fill(0);input[0]=15000;
  double tail=0,stereo=0;for(int n=0;n<rate*2/480;n++){fx.process(input.data(),480,1,output.data());input.fill(0);if(n>rate/480/4)for(int i=0;i<480;i++){tail+=std::abs(output[2*i]);stereo+=std::abs(output[2*i]-output[2*i+1]);}}
  check(tail>1000,"reverb tail");check(stereo>100,"stereo reverb");
  v[0]=12;v[1]=8;v[6]=70;v[4]=60;v[8]=12;v[9]=12;v[11]=70;v[12]=100;v[16]=70;v[17]=6;
  fx.configure(true,v.data());auto t0=std::chrono::steady_clock::now();auto loud=run(fx,rate,225,3);
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
  int peak=0;for(auto sample:loud)peak=std::max(peak,std::abs(int(sample)));
  check(peak<=29491,"limiter ceiling");check(input==std::array<int16_t,480>{},"input remains untouched");
  printf("rate=%d worst_chain_ms_per_10ms=%.3f peak=%d\n",rate,ms/300,peak);
  v[0]=99;check(!fx.configure(true,v.data()),"reject invalid range");
  fx.configure(false,defaults().data());fx.process(before.data(),480,1,output.data());
  check(output[4]==before[2],"restore bypass");
 }
 puts("PASS vocal DSP: pitch, tune, tail, stereo, limiter, bypass and validation");
}
