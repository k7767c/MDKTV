#include "vocal_fx.h"
#include "signalsmith/signalsmith-stretch.h"
#include "NRev.h"
#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

namespace {
constexpr float pi=3.14159265358979323846f;
std::mutex stkSetup;
float clamp(float x,float lo,float hi){return std::max(lo,std::min(hi,x));}
const float lows[]={-12,-12,0,.2f,0,60,0,0,-12,-12,0,0,0,0,0,0,0,-12};
const float highs[]={12,12,70,6,60,600,70,100,12,12,100,70,100,11,2,100,100,6};
}
struct VocalFx::Impl {
 int rate,block;bool enabled=false,shift=false;
 std::array<float,count> v{{0,0,0,1.2f,0,160,0,50,0,0,0,0,0,0,0,0,0,0}};
 signalsmith::stretch::SignalsmithStretch<float> stretch;
 std::unique_ptr<stk::NRev> reverb;
 std::vector<float> echo,chorus;
 size_t ep=0,cp=0;float phase=0,robotPhase=0,lo=0,hi=0,vinyl=0,env=0,limiter=1;
 std::array<float,256> mono{},shifted{};
 std::array<float,512> pitch{};int pitchWrite=0,pitchSamples=0,pitchTick=0,downCount=0;float downSum=0,midi=-1;
 float correction=0;
 explicit Impl(int sr):rate(sr),echo(sr),chorus(sr/10) {
  // Small shifts need 60 ms; large shifts at the TV's 48 kHz can use 40 ms.
  block=static_cast<int>(rate*.06f);
  stretch.configure(1,block,block/4,false);
  stretch.setFormantBase(180.f/rate);
  {std::lock_guard<std::mutex> lock(stkSetup);stk::Stk::setSampleRate(rate);reverb=std::make_unique<stk::NRev>(1.2);}
  reverb->setEffectMix(1);
 }
 float tap(const std::vector<float>& data,size_t pos,float delay){
  float x=float(pos)-delay;while(x<0)x+=data.size();int i=int(x)%data.size();float f=x-int(x);
  return data[i]*(1-f)+data[(i+1)%data.size()]*f;
 }
 void detect(float value){
  const int stride=std::max(1,rate/8000);
  downSum+=value;if(++downCount<stride)return;
  pitch[pitchWrite]=downSum/downCount;pitchWrite=(pitchWrite+1)%512;pitchSamples++;downCount=0;downSum=0;
  if(++pitchTick<160||pitchSamples<512)return;pitchTick=0;
  std::array<float,512> x{};float mean=0,energy=0;
  for(int i=0;i<512;i++){x[i]=pitch[(pitchWrite+i)%512];mean+=x[i]/512;}
  for(float& a:x){a-=mean;energy+=a*a;}
  if(energy/512<.000064f){midi=-1;return;}
  float sr=float(rate)/stride;int low=int(sr/800),high=std::min(200,int(sr/70));
  std::array<float,202> corr{};float best=0;
  for(int lag=low;lag<=high;lag++){
   float sum=0,a=0,b=0;for(int i=0;i<512-lag;i++){sum+=x[i]*x[i+lag];a+=x[i]*x[i];b+=x[i+lag]*x[i+lag];}
   corr[lag]=sum/std::sqrt(std::max(1e-12f,a*b));best=std::max(best,corr[lag]);
  }
  midi=-1;
  for(int lag=low+1;lag<high;lag++)if(corr[lag]>=std::max(.84f,best*.96f)&&corr[lag]>=corr[lag-1]&&corr[lag]>corr[lag+1]){
   float den=corr[lag-1]-2*corr[lag]+corr[lag+1];
   float refined=lag+(std::abs(den)>1e-6f?.5f*(corr[lag-1]-corr[lag+1])/den:0);
   midi=69+12*std::log2((sr/refined)/440);break;
  }
 }
 float quantized(float note){
  float best=note,dist=100;int key=int(v[13]),scale=int(v[14]);
  for(int n=int(std::floor(note))-12;n<=int(std::ceil(note))+12;n++){
   int k=((n-key)%12+12)%12;
   bool allowed=scale==0||(scale==1?(k==0||k==2||k==4||k==5||k==7||k==9||k==11):(k==0||k==2||k==3||k==5||k==7||k==8||k==10));
   if(allowed&&std::abs(n-note)<dist){dist=std::abs(n-note);best=n;}
  }return best;
 }
 void clear(){stretch.reset();reverb->clear();std::fill(echo.begin(),echo.end(),0);std::fill(chorus.begin(),chorus.end(),0);lo=hi=vinyl=env=0;limiter=1;pitch.fill(0);pitchSamples=pitchTick=pitchWrite=downCount=0;downSum=0;midi=-1;correction=0;}
};
VocalFx::VocalFx(int sr):p(new Impl(sr)){}
VocalFx::~VocalFx()=default;
bool VocalFx::configure(bool enabled,const float* values){
 for(int i=0;i<count;i++)if(!std::isfinite(values[i])||values[i]<lows[i]||values[i]>highs[i])return false;
 if(values[13]!=std::floor(values[13])||values[14]!=std::floor(values[14]))return false;
 auto& x=*p;bool shift=std::abs(values[0])>.01f||std::abs(values[1])>.01f||values[12]>0;
 bool fastShift=x.rate==48000&&std::abs(values[0])>=7;
 int block=static_cast<int>(x.rate*(values[12]>0?.12f:(fastShift?.04f:.06f)));
 bool resized=block!=x.block;
 if(resized){x.block=block;x.stretch.configure(1,block,block/4,false);x.stretch.setFormantBase(180.f/x.rate);}
 if(x.enabled!=enabled||x.shift!=shift||resized)x.clear();
 x.enabled=enabled;x.shift=shift;
 if(values[3]!=x.v[3]){std::lock_guard<std::mutex> lock(stkSetup);stk::Stk::setSampleRate(x.rate);x.reverb->setT60(values[3]);}
 std::copy(values,values+count,x.v.begin());return true;
}
int VocalFx::latency()const{return p->enabled&&p->shift?p->stretch.inputLatency()+p->stretch.outputLatency():0;}
void VocalFx::process(const int16_t* input,int frames,int channels,int16_t* out){
 auto& x=*p;auto& v=x.v;
 if(!x.enabled){for(int i=0;i<frames;i++){out[2*i]=input[i*channels];out[2*i+1]=input[i*channels+channels-1];}return;}
 float bass=std::pow(10.f,v[8]/20)-1,treble=std::pow(10.f,v[9]/20)-1,gain=std::pow(10.f,v[17]/20);
 float lowA=1-std::exp(-2*pi*220/x.rate),highA=1-std::exp(-2*pi*3500/x.rate);
 float wet=v[2]/100,echoMix=v[4]/100,chorusMix=v[6]/100,width=v[7]/100;
 float echoSamples=x.rate*v[5]/1000;
 float comp=v[10]/100,drive=1+v[11]*.06f,vinylMix=v[15]/100,robot=v[16]/100;
 for(int start=0;start<frames;start+=256){int n=std::min(256,frames-start);
  for(int i=0;i<n;i++){
   float a=0;for(int c=0;c<channels;c++)a+=input[(start+i)*channels+c]/32768.f/channels;
   if(v[12]>0)x.detect(a);
   x.mono[i]=a;
  }
  if(x.shift){
   float correction=x.midi>=0?(x.quantized(x.midi+v[0])-x.midi-v[0])*v[12]/100:0;
   x.correction+=(correction-x.correction)*(v[12]>=90?1:.2f);
      x.stretch.setTransposeSemitones(v[0]+x.correction);
   x.stretch.setFormantSemitones(v[1],true);
   const float* in[]={x.mono.data()};float* dest[]={x.shifted.data()};x.stretch.process(in,n,dest,n);
  }
  for(int i=0;i<n;i++){
   float a=x.shift?x.shifted[i]:x.mono[i];
   x.lo+=lowA*(a-x.lo);x.hi+=highA*(a-x.hi);a+=bass*x.lo+treble*(a-x.hi);
   float level=std::abs(a);x.env+=(level-x.env)*(level>x.env?.02f:.0005f);
   if(comp>0&&x.env>.18f)a*=std::pow(.18f/x.env,comp*.8f);
   if(v[11]>0)a=std::tanh(a*drive)/std::sqrt(drive);
   x.vinyl+=.25f*(a-x.vinyl);a=a*(1-vinylMix)+x.vinyl*vinylMix;
   x.robotPhase+=2*pi*70/x.rate;if(x.robotPhase>2*pi)x.robotPhase-=2*pi;
   a*=1-robot+robot*std::cos(x.robotPhase);
   float e=x.tap(x.echo,x.ep,echoSamples);x.echo[x.ep]=a+e*.28f;x.ep=(x.ep+1)%x.echo.size();a+=e*echoMix;
   x.chorus[x.cp]=a;
   x.phase+=2*pi*.7f/x.rate;if(x.phase>2*pi)x.phase-=2*pi;
   float cl=x.tap(x.chorus,x.cp,x.rate*(.018f+.003f*std::sin(x.phase)));
   float cr=x.tap(x.chorus,x.cp,x.rate*(.021f+.003f*std::sin(x.phase+1.8f)));
   x.cp=(x.cp+1)%x.chorus.size();
   float rl=x.reverb->tick(a,0),rr=x.reverb->lastOut(1);
   float l=(a+cl*chorusMix)*(1-wet)+rl*wet,r=(a+cr*chorusMix)*(1-wet)+rr*wet;
   float mid=(l+r)*.5f;l=mid+(l-mid)*width*2;r=mid+(r-mid)*width*2;l*=gain;r*=gain;
   float peak=std::max(std::abs(l),std::abs(r));float limit=peak>.88f?.88f/peak:1;
   x.limiter=limit<x.limiter?limit:x.limiter+(limit-x.limiter)*.0005f;
   if(!std::isfinite(l)||!std::isfinite(r)){l=r=0;x.clear();}
   out[2*(start+i)]=int16_t(clamp(l*x.limiter,-.9f,.9f)*32767);
   out[2*(start+i)+1]=int16_t(clamp(r*x.limiter,-.9f,.9f)*32767);
  }
 }
}
