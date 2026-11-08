"""Resolve channel versions and freeze deterministic, secret-free source snapshots."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import tempfile
import zipfile

def read_config(path):
    return dict(line.strip().split('=',1) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip() and not line.startswith('#'))

def resolve(config, date, source_sha=None):
    channel=config['channel'];base=config['baseVersion'];iteration=int(config['iteration']);code=int(config['versionCode'])
    if channel not in ('Dev','Beta','Release') or not re.fullmatch(r'\d+\.\d+\.\d+',base):raise ValueError('Invalid channel/base version')
    major,minor,patch=map(int,base.split('.'))
    if not (0<=patch<=999 and code>0):raise ValueError('Invalid patch/versionCode')
    if channel=='Dev' and not (0<=iteration<=49 or 100<=iteration<=499):raise ValueError('Dev iteration must be 0..49 or 100..499')
    if channel=='Beta' and not (50<=iteration<=99 or 500<=iteration<=999):raise ValueError('Beta iteration must be 50..99 or 500..999')
    if channel=='Release' and (major<1 or iteration!=0):raise ValueError('Release starts at 1.0.0.0; fourth component is 0')
    if not re.fullmatch(r'\d{8}',date):raise ValueError('Invalid build date')
    datetime.datetime.strptime(date,'%Y%m%d')
    if channel!='Dev' and not re.fullmatch(r'[0-9a-f]{64}',source_sha or ''):raise ValueError('Beta/Release require the frozen source snapshot SHA256')
    return dict(versionName=f'{channel}.{base}.{iteration}_{date if channel=="Dev" else source_sha[:7].upper()}',
        applicationId='dev.realize.starmark' if channel=='Dev' else 'com.realize.starmark',
        versionCode=code, channel=channel, sourceSha256=source_sha)

def snapshot(root, output):
    root=Path(root).resolve();output=Path(output).resolve();output.mkdir(parents=True,exist_ok=True)
    paths=[]
    roots=['lib','assets','packages','android','scripts','pubspec.yaml','pubspec.lock','version.properties','analysis_options.yaml']
    excluded={'.git','.gradle','.kotlin','.tooling','.dart_tool','build','.idea','__pycache__','sign','signing','node_modules','.cxx'}
    names={'local.properties','key.properties','credentials.properties','signing.local.properties','GeneratedPluginRegistrant.java'}
    for name in roots:
        start=root/name
        for p in ([start] if start.is_file() else start.rglob('*') if start.exists() else []):
            rel=p.relative_to(root)
            if not p.is_file() or p.is_symlink() or any(part in excluded for part in rel.parts):continue
            if p.name in names or p.suffix.lower() in ('.jks','.keystore','.p12','.pfx','.pem','.apk','.log'):continue
            if 'credentials' in p.name.lower() or 'password' in p.name.lower():continue
            paths.append(p)
    with tempfile.NamedTemporaryFile(suffix='.zip',dir=output,delete=False) as temp:tmp=Path(temp.name)
    try:
        with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
            for p in sorted(paths,key=lambda p:p.relative_to(root).as_posix()):
                entry=zipfile.ZipInfo(p.relative_to(root).as_posix(),date_time=(1980,1,1,0,0,0))
                entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o100644<<16
                archive.writestr(entry,p.read_bytes())
        with tmp.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
        destination=output/f'StarMark-source-{digest}.zip'
        if destination.exists():tmp.unlink()
        else:tmp.replace(destination)
        return dict(sourceSha256=digest,snapshot=str(destination),sourceFiles=len(paths))
    finally:
        if tmp.exists():tmp.unlink()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--date',default=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y%m%d'))
    parser.add_argument('--snapshot',action='store_true')
    args=parser.parse_args();config=read_config(args.root/'version.properties')
    frozen=snapshot(args.root,args.root/'artifacts/source-snapshots') if args.snapshot else {}
    print(json.dumps({**resolve(config,args.date,frozen.get('sourceSha256')),**frozen}))
