#!/usr/bin/env python3
"""
Couchbase FHIR CE - Configuration Generator
Reads config.yaml and generates docker-compose.yml and haproxy.cfg
"""

import sys
import yaml
from pathlib import Path
from datetime import datetime
from urllib.parse import urlparse

def load_config(config_path):
    """Load and validate config.yaml"""
    with open(config_path, 'r') as f:
        config = yaml.safe_load(f)
    
    # Validate required fields
    required = ['app', 'couchbase', 'admin', 'deploy']
    for field in required:
        if field not in config:
            raise ValueError(f"Missing required field: {field}")
    
    return config

def auto_detect_ports(base_url):
    """Auto-detect ports from baseUrl"""
    parsed = urlparse(base_url)
    
    if parsed.scheme == 'https':
        return 80, 443
    elif 'localhost' in parsed.netloc or '127.0.0.1' in parsed.netloc:
        # Development: use 8080 if specified in URL
        if ':8080' in parsed.netloc:
            return 8080, 8443
        return 80, 443
    else:
        return 80, 443

def generate_docker_compose(config):
    """Generate docker-compose.yml from simplified config"""
    app = config['app']
    deploy = config['deploy']
    
    # Auto-detect ports
    http_port, https_port = auto_detect_ports(app['baseUrl'])
    tls_enabled = deploy['tls']['enabled']
    
    # Container resources
    mem_limit = deploy['container']['mem_limit']
    mem_reservation = deploy['container']['mem_reservation']
    
    # JVM settings
    xms = deploy['jvm']['xms']
    xmx = deploy['jvm']['xmx']
    
    # Build environment variables
    env = {
        'JAVA_TOOL_OPTIONS': f'-Xms{xms} -Xmx{xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=200',
        'APP_BASE_URL': app['baseUrl']  # OAuth issuer needs this
    }
    
    # Add environment overrides
    env_overrides = deploy.get('environment', {}).get('overrides')
    if env_overrides:
        env.update(env_overrides)
    
    compose = {
        'services': {
            'fhir-server': {
                'build': {
                    'context': './backend',
                    'dockerfile': 'Dockerfile'
                },
                'image': 'ghcr.io/couchbaselabs/couchbase-fhir-ce/fhir-server:latest',
                'container_name': 'fhir-server',
                'environment': env,
                'volumes': [
                    './config.yaml:/config.yaml:ro',
                    './logs:/app/logs'
                ],
                'restart': 'unless-stopped',
                'deploy': {
                    'resources': {
                        'limits': {'memory': mem_limit},
                        'reservations': {'memory': mem_reservation}
                    }
                },
                'logging': {
                    'driver': 'json-file',
                    'options': {
                        'max-size': '50m',
                        'max-file': '3'
                    }
                }
            },
            
            'fhir-admin': {
                'build': {
                    'context': './frontend',
                    'dockerfile': 'Dockerfile'
                },
                'image': 'ghcr.io/couchbaselabs/couchbase-fhir-ce/fhir-admin:latest',
                'container_name': 'fhir-admin',
                'restart': 'unless-stopped',
                'deploy': {
                    'resources': {
                        'limits': {'memory': '512m'},
                        'reservations': {'memory': '256m'}
                    }
                }
            },
            
            'haproxy': {
                'image': 'haproxy:2.8-alpine',
                'container_name': 'haproxy',
                'ports': [f'{http_port}:80'],
                'volumes': ['./haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro'],
                'restart': 'unless-stopped',
                'depends_on': ['fhir-server', 'fhir-admin']
            }
        }
    }
    
    # Add HTTPS port and PEM volume if TLS enabled
    if tls_enabled:
        pem_path = deploy['tls'].get('pemPath', './certs/server.pem')
        compose['services']['haproxy']['ports'].append(f'{https_port}:443')
        compose['services']['haproxy']['volumes'].append(f'{pem_path}:/etc/haproxy/certs/server.pem:ro')
    
    return compose

def generate_haproxy_config(config):
    """Generate haproxy.cfg from simplified config"""
    tls_enabled = config['deploy']['tls']['enabled']
    
    cfg = f"""# =============================================================================
# HAProxy Configuration - AUTO-GENERATED from config.yaml
# =============================================================================
# Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
# ⚠️  DO NOT EDIT MANUALLY - Run: ./scripts/apply-config.sh to regenerate
# =============================================================================

global
    log stdout format raw local0
    maxconn 4096
    tune.ssl.default-dh-param 2048

defaults
    log     global
    mode    http
    option  httplog
    option  dontlognull
    timeout connect 5000ms
    timeout client  50000ms
    timeout server  50000ms
    option  forwardfor
    option  http-server-close

# HAProxy Stats Page
listen stats
    bind *:8404
    stats enable
    stats uri /haproxy?stats
    stats refresh 5s
    stats auth admin:admin

frontend http-in
    bind *:80
"""
    
    if tls_enabled:
        cfg += """    bind *:443 ssl crt /etc/haproxy/certs/server.pem
    http-request redirect scheme https unless { ssl_fc }

"""
    
    cfg += """    # Route backend services (API, OAuth, FHIR, health)
    acl is_backend path_beg /api /fhir /oauth2 /login /consent /patient-picker /.well-known /health
    use_backend backend-fhir-server if is_backend
    
    # Default: route to frontend Admin UI
    default_backend backend-fhir-admin

backend backend-fhir-admin
    balance roundrobin
    option httpchk HEAD /
    server frontend fhir-admin:80 check

backend backend-fhir-server
    balance roundrobin
    option httpchk GET /health
    server backend fhir-server:8080 check
"""
    
    return cfg

def write_file(path, content, backup=True):
    """Write file with optional backup"""
    path = Path(path)
    
    if backup and path.exists():
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_path = path.with_suffix(path.suffix + f'.bak.{timestamp}')
        path.rename(backup_path)
        print(f"📦 Backup: {backup_path}")
    
    with open(path, 'w') as f:
        if isinstance(content, dict):
            yaml.dump(content, f, default_flow_style=False, sort_keys=False)
        else:
            f.write(content)
    
    print(f"✅ Generated: {path}")

def main():
    if len(sys.argv) < 2:
        print("Usage: ./scripts/generate.py <config.yaml>")
        print("Example: ./scripts/generate.py ./config.yaml")
        sys.exit(1)
    
    config_path = sys.argv[1]
    
    try:
        print(f"📝 Reading configuration: {config_path}")
        config = load_config(config_path)
        
        # Show key settings
        http_port, https_port = auto_detect_ports(config['app']['baseUrl'])
        tls = "Enabled" if config['deploy']['tls']['enabled'] else "Disabled"
        mem = f"{config['deploy']['jvm']['xms']} - {config['deploy']['jvm']['xmx']}"
        
        print(f"🌐 Base URL: {config['app']['baseUrl']}")
        print(f"🚪 Ports: HTTP={http_port}, HTTPS={https_port}")
        print(f"🔒 TLS: {tls}")
        print(f"💾 JVM Memory: {mem}")
        print()
        
        print("🐳 Generating docker-compose.yml...")
        compose = generate_docker_compose(config)
        write_file('docker-compose.yml', compose)
        
        print("🔀 Generating haproxy.cfg...")
        haproxy_cfg = generate_haproxy_config(config)
        write_file('haproxy.cfg', haproxy_cfg)
        
        print("\n✅ Generation complete!")
        print("\n📌 Next steps:")
        print("   docker-compose up -d")
        
    except Exception as e:
        print(f"\n❌ Error: {e}")
        sys.exit(1)

if __name__ == '__main__':
    main()
