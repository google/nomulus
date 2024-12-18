#! /bin/env python3
import ipaddress
import subprocess
from dataclasses import dataclass
from ipaddress import IPv4Address
from ipaddress import IPv6Address
from operator import attrgetter
from operator import methodcaller


class PreserveContext:
    def __enter__(self):
        self._context = run_command('kubectl config current-context')

    def __exit__(self, type, value, traceback):
        run_command('kubectl config use-context ' + self._context)


class UseCluster(PreserveContext):
    def __init__(self, cluster: str, region: str, project: str):
        self._cluster = cluster
        self._region = region
        self._project = project

    def __enter__(self):
        super().__enter__()
        cmd = f'gcloud container clusters get-credentials {self._cluster} --location {self._region} --project {self._project}'
        run_command(cmd)


def run_command(cmd: str, print_output=False) -> str:
    proc = subprocess.run(cmd, text=True, shell=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT)
    if print_output:
        print(proc.stdout)
    return proc.stdout


def get_clusters(project: str) -> dict[str, str]:
    cmd = 'gcloud container clusters list --project ' + project
    lines = run_command(cmd)
    res = {}
    for line in lines.split('\n'):
        if not line.startswith('nomulus-cluster'):
            continue
        parts = line.split()
        res[parts[0]] = parts[1]
    return res


def get_endpoints(service: str, resource: str = 'services',
        ip_idx: int = 3) -> list[str]:
    res = []
    lines = run_command(f'kubectl get {resource}/{service}')
    for line in lines.split('\n'):
        if not line.startswith(service):
            continue
        res.extend(line.split()[ip_idx].split(','))
    return res


def is_ipv6(addr: str) -> bool:
    return ':' in addr


def get_region_symbol(region: str) -> str:
    if region.startswith('us'):
        return 'amer'
    if region.startswith('europe'):
        return 'emea'
    if region.startswith('asia'):
        return 'apac'
    return 'other'


@dataclass
class IP:
    service: str
    region: str
    address: IPv4Address | IPv6Address

    def is_ipv6(self) -> bool:
        return self.address.version == 6

    def __str__(self) -> str:
        return f'{self.service} {self.region}: {self.address}'


def terraform_str(item) -> str:
    res = ""
    if (isinstance(item, dict)):
        res += '{\n'
        for key, value in item.items():
            res += f'{key} = {terraform_str(value)}\n'
        res += '}'
    elif (isinstance(item, list)):
        res += '['
        for i, value in enumerate(item):
            if i != 0:
                res += ', '
            res += terraform_str(value)
        res += ']'
    else:
        res += f'"{item}"'
    return res


if __name__ == '__main__':
    project = 'domain-registry-alpha'
    clusters = get_clusters(project)
    ips = []
    res = {}
    for cluster, region in clusters.items():
        with UseCluster(cluster, region, project):
            for service in ['whois', 'whois-canary', 'epp', 'epp-canary']:
                map_key = service.replace('-', '_')
                for ip in get_endpoints(service):
                    ip = ipaddress.ip_address(ip)
                    if isinstance(ip, IPv4Address):
                        map_key_with_iptype = map_key + '_ipv4'
                    else:
                        map_key_with_iptype = map_key + '_ipv6'
                    if map_key_with_iptype not in res:
                        res[map_key_with_iptype] = {}
                    res[map_key_with_iptype][get_region_symbol(region)] = [ip]
                    ips.append(IP(service, get_region_symbol(region), ip))
            if not region.startswith('us'):
                continue
            ip = \
                get_endpoints('nomulus', 'gateways.gateway.networking.k8s.io',
                              2)[0]
            print(f'nomulus: {ip}')
            res['https_ip'] = ipaddress.ip_address(ip)
    ips.sort(key=attrgetter('region'))
    ips.sort(key=methodcaller('is_ipv6'))
    ips.sort(key=attrgetter('service'))
    for ip in ips:
        print(ip)
    print(terraform_str(res))
