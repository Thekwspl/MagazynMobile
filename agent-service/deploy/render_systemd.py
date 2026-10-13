#!/usr/bin/env python3
"""Render reviewable units. No installation, restart or enable operation is performed."""
import argparse, json
from pathlib import Path
from updater import load_config

def render(config,output,updater_path,config_path,interval=30):
    if not 5<=interval<=1440: raise ValueError('INTERVAL_INVALID')
    if not Path(updater_path).is_absolute() or not Path(config_path).is_absolute(): raise ValueError('PATH_INVALID')
    output.mkdir(parents=True,exist_ok=True)
    (output/'magazyn-agent-update.service').write_text(f'''[Unit]
Description=Check approved MagazynMobile agent release
After=network-online.target {config['service']}
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {updater_path} --config {config_path} --apply
UMask=0022
TimeoutStartSec=10min
''')
    (output/'magazyn-agent-update.timer').write_text(f'''[Unit]
Description=Check agent after boot and while VM is running

[Timer]
OnBootSec=2min
OnUnitInactiveSec={interval}min
RandomizedDelaySec=30s
Unit=magazyn-agent-update.service

[Install]
WantedBy=timers.target
''')
    (output/'50-magazyn-update.conf').write_text(f'''[Service]
WorkingDirectory={config['current']}
ExecStart=
ExecStart={config['node']} {config['current']}/dist/src/server.js
Environment=AGENT_UPDATE_TOKEN_FILE={config['update_token_file']}
''')
if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--config',type=Path,required=True); p.add_argument('--output',type=Path,required=True); p.add_argument('--updater-path',required=True); p.add_argument('--interval-minutes',type=int,default=30); a=p.parse_args()
    render(load_config(a.config),a.output,a.updater_path,str(a.config.resolve()),a.interval_minutes)
