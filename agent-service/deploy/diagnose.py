#!/usr/bin/env python3
"""Read-only VM inventory. Never dump environment values, journals or Codex credentials."""
import argparse, json, os, re, shutil, ssl, socket, subprocess, urllib.request, urllib.parse
from pathlib import Path

def run(args):
    try: return subprocess.run(args,text=True,capture_output=True,timeout=15).stdout.strip()
    except (OSError,subprocess.SubprocessError): return ''
def diagnose(service=None,domain=None):
    report={'node':shutil.which('node'),'nodeVersion':run(['node','--version']), 'codex':shutil.which('codex'),
            'freeBytes':shutil.disk_usage('/').free,
            'units':run(['systemctl','list-unit-files','--type=service','--no-legend']).splitlines(),
            'reverseProxy':{name:run(['systemctl','is-active',name]) for name in ['nginx','caddy','apache2']}}
    if service:
        if not re.fullmatch(r'[A-Za-z0-9_.@-]+\.service',service): raise ValueError('SERVICE_INVALID')
        # ExecStart and Environment values can contain secrets. Only expose executable path, never argv/env.
        for key in ['User','Group','WorkingDirectory','EnvironmentFiles','FragmentPath','DropInPaths','Restart']:
            report[key]=run(['systemctl','show',service,'--property='+key,'--value'])
        exec_start=run(['systemctl','show',service,'--property=ExecStart','--value'])
        report['executable']=re.search(r'path=([^ ;}]+)',exec_start).group(1) if re.search(r'path=([^ ;}]+)',exec_start) else None
        report['active']=run(['systemctl','is-active',service])
    if domain:
        if not re.fullmatch(r'[a-zA-Z0-9.-]+',domain): raise ValueError('DOMAIN_INVALID')
        try:
            report['DNS']=sorted(set(row[4][0] for row in socket.getaddrinfo(domain,443)))
            with socket.create_connection((domain,443),timeout=10) as connection:
                with ssl.create_default_context().wrap_socket(connection,server_hostname=domain) as tls:
                    cert=tls.getpeercert(); report['TLS']={'validated':True,'notAfter':cert.get('notAfter'),'subjectAltName':cert.get('subjectAltName')}
            with urllib.request.urlopen('https://'+domain+'/health',timeout=15) as response: report['HTTPSStatus']=response.status
        except (OSError,ValueError): report['TLS']={'validated':False,'stage':'DNS/TLS/HTTPS'}
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--service'); p.add_argument('--domain'); a=p.parse_args()
    print(json.dumps(diagnose(a.service,a.domain),indent=2))
