"""Build the actual Android DSP for browser AudioWorklets, without JNI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'app/src/main/cpp'

def build(emsdk, out):
    emsdk, out = emsdk.resolve(), out.resolve()
    compiler = next((emsdk/'upstream/emscripten'/name for name in ('em++.py','em++.exe','em++.bat','em++')
                     if (emsdk/'upstream/emscripten'/name).exists()),emsdk/'missing-compiler')
    if not compiler.exists():
        raise ValueError('Emscripten compiler is required; use the official portable emsdk.')
    out.mkdir(parents=True, exist_ok=True)
    sources = [ROOT/'tools/web_dsp.cpp', CPP/'vocal_fx.cpp', CPP/'music_dsp.cpp',
               *(CPP/'third_party/stk/src').glob('*.cpp'),
               *(CPP/'third_party/dspfilters/source').glob('*.cpp')]
    invoke = [sys.executable,str(compiler)] if compiler.suffix == '.py' else [str(compiler)]
    command = [*invoke, *map(str, sources), '-O3', '-std=c++17',
               '-I'+str(CPP), '-I'+str(CPP/'third_party'),
               '-I'+str(CPP/'third_party/stk/include'), '-I'+str(CPP/'third_party/dspfilters/include'),
               '-sMODULARIZE=1', '-sEXPORT_ES6=1', '-sEXPORT_NAME=createDsp',
               '-sINCOMING_MODULE_JS_API=wasmBinary,locateFile,print,printErr',
               '--js-library', str(ROOT/'tools/web_dsp_random.js'),
               '-sENVIRONMENT=web,worker,node', '-sALLOW_MEMORY_GROWTH=1',
               '-sINITIAL_MEMORY=33554432', '-sMAXIMUM_MEMORY=134217728',
               '-sFILESYSTEM=0', '-sASSERTIONS=0', '-sEXPORTED_RUNTIME_METHODS=HEAP16,HEAPF32',
               '-sEXPORTED_FUNCTIONS=_malloc,_free,_fx_create,_fx_config,_fx_process,_fx_latency,_fx_destroy,_music_create,_music_config,_music_pitch,_music_latency,_music_process,_music_destroy',
               '-o', str(out/'feiniu-dsp.js')]
    environment = dict(os.environ, EM_CONFIG=str(emsdk/'.emscripten'))
    subprocess.run(command, check=True, cwd=ROOT, env=environment)
    hashes = lambda files: {str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    inputs = sources + list((CPP/'third_party').rglob('*.h')) + [CPP/'vocal_fx.h', CPP/'music_dsp.h', Path(__file__).resolve(), ROOT/'tools/web_dsp_random.js']
    report = {'version':1, 'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
              'compiler':subprocess.check_output([*invoke,'--version'],cwd=ROOT,env=environment).decode().splitlines()[0],
              'recordedSourceDigests':hashes(inputs),
              'hashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (out/'feiniu-dsp.js',out/'feiniu-dsp.wasm')}}
    (out/'DSP-BUILD.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({'out':str(out),'compiler':report['compiler'],'hashes':report['hashes']}))

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--emsdk',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    arguments=parser.parse_args()
    build(arguments.emsdk,arguments.out)
