#! /bin/env python3
# Copyright 2024 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

'''
A script that outputs the IP endpoints of various load balancers, to be run
after Nomulus is deployed.
'''

import ipaddress
import json
import subprocess
import sys
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
    cmd = f'gcloud container clusters list --project {project} --format=json'
    content = json.loads(run_command(cmd))
    res = {}
    for item in content:
        name = item['name']
        region = item['location']
        if not name.startswith('nomulus-cluster'):
            continue
        res[name] = region
    return res


def get_endpoints(resource: str, service: str, selector: list[str]) -> list[
    str]:
    content = json.loads(
            run_command(f'kubectl get {resource}/{service} -o json'))
    name = content['metadata']['name']
    ips = content
    for key in selector[:-1]:
        ips = ips[key]
    return [x[selector[-1]] for x in ips]


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


projects = {"domain-registry", "domain-registry-sandbox",
            "domain-registry-crash", "domain-registry-crash",
            "domain-registry-alpha"}

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise ValueError('Usage: get-endpoints.py <project>')
    project = sys.argv[1]
    if (project not in projects):
        raise ValueError(f'Invalid project: {project}')
    clusters = get_clusters(project)
    ips = []
    res = {}
    for cluster, region in clusters.items():
        with UseCluster(cluster, region, project):
            for service in ['whois', 'whois-canary', 'epp', 'epp-canary']:
                map_key = service.replace('-', '_')
                for ip in get_endpoints('services', service,
                                        ['status', 'loadBalancer', 'ingress',
                                         'ip']):
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
            ip = get_endpoints('gateways.gateway.networking.k8s.io', 'nomulus',
                               ['status', 'addresses', 'value'])[0]
            print(f'nomulus: {ip}')
            res['https_ip'] = ipaddress.ip_address(ip)
    ips.sort(key=attrgetter('region'))
    ips.sort(key=methodcaller('is_ipv6'))
    ips.sort(key=attrgetter('service'))
    for ip in ips:
        print(ip)
    print("Terraform friendly output:")
    print(terraform_str(res))
