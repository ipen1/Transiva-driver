#!/usr/bin/env python3
"""Prepare a verified emulator before emulator-runner; retry SDK downloads, never tests."""
from pathlib import Path
import argparse,json,os,re,shutil,subprocess,tempfile,time
import xml.etree.ElementTree as ET

RETRY_MARKERS=(
    'error on zipfile unknown archive','zip end header not found','invalid zip',
    'not a zip file','unexpected end of archive','unexpected end of file',
    'checksum mismatch','connection reset','connection timed out','read timed out',
    'temporary failure in name resolution','http 502','http 503','http 504',
)
PERMANENT_MARKERS=('no space left on device','permission denied',
                   'failed to find package','unknown argument','unsupported class version',
                   'error while loading shared libraries')

def retryable(text):
    value=text.lower()
    license_rejected=re.search(r'licen[sc]e[^\n]*(?:not accepted|not been accepted|must be accepted)',value)
    return not license_rejected and not any(x in value for x in PERMANENT_MARKERS) and any(x in value for x in RETRY_MARKERS)

def verify(directory,env,log):
    try:
        metadata=ET.parse(directory/'package.xml').getroot()
        if not any(node.tag.rsplit('}',1)[-1]=='localPackage' and node.get('path')=='emulator' for node in metadata.iter()):return False,'emulator SDK package metadata is invalid'
    except (OSError,ET.ParseError):return False,'emulator SDK package metadata missing or invalid'
    binary=directory/'emulator'
    if not binary.is_file() or not os.access(binary,os.X_OK):return False,'emulator executable missing'
    try:
        result=subprocess.run([str(binary),'-version'],env=env,capture_output=True,text=True,timeout=30)
        output=result.stdout+result.stderr;log.write(output);log.flush()
        return result.returncode==0 and 'android emulator version' in output.lower(),output.strip()
    except (OSError,subprocess.TimeoutExpired) as error:return False,str(error)

def promote(source,sdk):
    # Stage the verified SDK package beside the target; preserve a working runner
    # emulator until the new directory can be committed on the same filesystem.
    stage=Path(tempfile.mkdtemp(prefix='.transiva-emulator-',dir=sdk))
    backup=stage.with_name(stage.name+'-previous');target=sdk/'emulator'
    try:
        shutil.copytree(source,stage,dirs_exist_ok=True,symlinks=True)
        if target.exists():target.rename(backup)
        try:stage.rename(target)
        except OSError:
            if backup.exists() and not target.exists():backup.rename(target)
            raise
        if backup.exists():shutil.rmtree(backup)
    finally:
        if stage.exists():shutil.rmtree(stage)

def prepare(sdk,manager,reports,attempts=3,backoff=3):
    reports.mkdir(parents=True,exist_ok=True)
    result={'status':'failed','attempts':0,'tests_started':False}
    try:
        if not sdk.is_dir():raise RuntimeError('ANDROID_HOME/ANDROID_SDK_ROOT must name an existing SDK directory')
        if not manager:raise RuntimeError('sdkmanager was not found on this runner')
        for attempt in range(1,attempts+1):
            result['attempts']=attempt;log_path=reports/f'sdk-prepare-attempt-{attempt}.log'
            # Each attempt gets a NEW SDK root, including a fresh .temp install area.
            # A corrupt partial archive can never be reused by the next attempt.
            with tempfile.TemporaryDirectory(prefix='transiva-emulator-download-') as temp:
                isolated=Path(temp)/'sdk';isolated.mkdir()
                if (sdk/'licenses').is_dir():shutil.copytree(sdk/'licenses',isolated/'licenses')
                env=os.environ.copy();env.update(ANDROID_HOME=str(isolated),ANDROID_SDK_ROOT=str(isolated))
                with log_path.open('w',encoding='utf-8') as log:
                    log.write(f'Emulator SDK preparation attempt {attempt}/{attempts}\n');log.flush()
                    try:
                        install=subprocess.run([manager,f'--sdk_root={isolated}','--install','emulator','--channel=0'],env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,timeout=360)
                        code=install.returncode
                    except subprocess.TimeoutExpired:
                        code=124;log.write('\nSDK download read timed out\n');log.flush()
                    ok,version=verify(isolated/'emulator',env,log) if code==0 else (False,'')
                text=log_path.read_text(encoding='utf-8',errors='replace')
                if code==0 and ok:
                    promote(isolated/'emulator',sdk)
                    result.update(status='ready',version=version);print(f'EMULATOR_SDK_READY after {attempt} attempt(s).')
                    return 0
                print(f'Emulator SDK preparation attempt {attempt} failed:\n'+'\n'.join(text.splitlines()[-25:]))
                if code==0:
                    print('Emulator package did not pass executable/version verification: '+version)
                    can_retry=not any(x in version.lower() for x in PERMANENT_MARKERS)
                else:can_retry=retryable(text)
                if not can_retry or attempt==attempts:
                    result['reason']='SDK installation or verification failed; instrumentation was not started.'
                    if code==0 and version:result['reason']+=' '+version
                    print('EMULATOR_SDK_SETUP_FAILED: '+result['reason']);return 1
            if backoff:time.sleep(backoff*attempt)
    except (OSError,RuntimeError) as error:
        result['reason']=str(error);print('EMULATOR_SDK_SETUP_FAILED: '+str(error));return 1
    finally:
        (reports/'sdk-prepare-result.json').write_text(json.dumps(result,indent=2)+'\n')
    return 1

def main():
    p=argparse.ArgumentParser();p.add_argument('--sdk-root',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT',''))
    p.add_argument('--report-dir',default='artifacts/android-runtime');args=p.parse_args()
    if not args.sdk_root:print('EMULATOR_SDK_SETUP_FAILED: Android SDK path is missing');return 1
    sdk=Path(args.sdk_root).resolve();manager=shutil.which('sdkmanager')
    if not manager:
        candidate=sdk/'cmdline-tools/latest/bin/sdkmanager';manager=str(candidate) if candidate.is_file() else None
    return prepare(sdk,manager,Path(args.report_dir))

if __name__=='__main__':raise SystemExit(main())
