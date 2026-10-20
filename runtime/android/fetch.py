#!/usr/bin/env python3
"""Fetch a locked source tree; never silently follow a moving branch."""
import json, pathlib, subprocess, sys
base=pathlib.Path(__file__).resolve().parent
name=sys.argv[1]; dest=pathlib.Path(sys.argv[2]).resolve()
spec=json.loads((base/'sources.json').read_text())[name]
def run(*args): subprocess.run(args,check=True)
if not (dest/'.git').exists():
    dest.mkdir(parents=True,exist_ok=True)
    run('git','init',str(dest))
    run('git','-C',str(dest),'remote','add','origin',spec['url'])
run('git','-C',str(dest),'fetch','--depth','1','origin',spec['commit'])
run('git','-C',str(dest),'checkout','--detach',spec['commit'])
actual=subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip()
if actual!=spec['commit']: raise SystemExit('source commit mismatch')
run('git','-C',str(dest),'submodule','update','--init','--recursive','--depth','1')
