"""Capacity regressions. Run after `mvn -pl np-benchmarks -am test`.

NP_CAPACITY_LIVE_TEST=1 enables local socket tests using the existing bench.cp dependency list.
"""
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from np_report import Report

ROOT = Path(__file__).resolve().parents[2]


class ReportTests(unittest.TestCase):
    def test_search_cap_is_a_lower_bound(self):
        events = [dict(type='run', settings={'capacity': {'variants': 'engine_java', 'max_users': 200}}),
                  dict(type='measurement', exp='capacity', config='engine_java', stage='search',
                       users=200, started=100, t=200, **{'pass': True}),
                  dict(type='variant', exp='capacity', config='engine_java', state='end', t=300)]
        report = Report(events).capacity()
        self.assertTrue(report['configs']['engine_java']['search_capped'])
        self.assertIn('>=200', report['headline'])
        events.insert(2, dict(type='measurement', exp='capacity', config='engine_java', stage='search',
                              users=300, started=200, t=250, **{'pass': False}))
        self.assertFalse(Report(events).capacity()['configs']['engine_java']['search_capped'])

    def test_connection_ceiling_cannot_be_the_search_or_overload_limit(self):
        script = (ROOT / 'np-benchmarks/scripts/capacity.sh').read_text()
        guard = script.split("<<'PY_CHECK'\n", 1)[1].split('\nPY_CHECK', 1)[0]
        for ceiling, expected in [('60000', 1), ('100000', 0)]:
            result = subprocess.run(['python3', '-c', guard, '500', '30000', ceiling, '1', '1.5 2'],
                                    capture_output=True)
            self.assertEqual(result.returncode, expected, result.stderr)

    def test_level_rejects_unverified_or_corrupt_results(self):
        script = (ROOT / 'np-benchmarks/scripts/capacity.sh').read_text()
        judge = script.split('<<PY\n', 1)[1].split('\nPY\n', 1)[0]
        for key, val in {'P95_MS': 500, 'P99_MS': 1000, 'ERROR_PCT': 1, 'MEMORY_MB': 2048}.items():
            judge = judge.replace('$' + key, str(val))
        base = dict(requests=10, errors=0, verified=10, mismatches=0, p95_us=100, p99_us=200)
        with tempfile.TemporaryDirectory() as tmp:
            result = Path(tmp) / 'result.json'
            for changes, expected in [({}, True), ({'verified': 0}, False),
                                      ({'mismatches': 1}, False), ({'requests': 0}, False)]:
                result.write_text(json.dumps(base | changes))
                output = subprocess.check_output(['python3', '-c', judge, str(result), 'engine_java',
                                                  'search', '10', '5', '100', '50', '1', '1'])
                self.assertEqual(json.loads(output)['pass'], expected)


@unittest.skipUnless(os.getenv('NP_CAPACITY_LIVE_TEST') == '1', 'enable local socket tests explicitly')
class ServerTests(unittest.TestCase):
    def test_all_servers_identity_limit_and_recovery(self):
        cp = ':'.join(str(ROOT / m / 'target/classes') for m in
                      ['np-benchmarks', 'np-binding', 'np-http-spring-starter', 'np-db'])
        cp += ':' + (ROOT / 'bench.cp').read_text().strip()
        variants = {'engine_java': ['RawServerBench'], 'raw_netty': ['RawNettyServer'],
                    'raw_tomcat': ['RawTomcatServer'], 'spring_nativepath': ['ServerBenchApp', 'nativepath'],
                    'spring_netty': ['ServerBenchApp', 'netty']}
        for name, args in variants.items():
            with self.subTest(server=name), tempfile.TemporaryFile(mode='w+') as log:
                with socket.socket() as reserved:
                    reserved.bind(('127.0.0.1', 0))
                    port = reserved.getsockname()[1]
                main = 'ro.bitboy.nativepath.benchmarks.' + args[0]
                command = ['java', '--enable-native-access=ALL-UNNAMED',
                           '-Dnativepath.lib=' + str(ROOT / ('target/release/libnativepath.dylib' if sys.platform == 'darwin' else 'target/release/libnativepath.so')),
                           '-cp', cp, main, *args[1:], str(port)]
                process = subprocess.Popen(command, cwd=ROOT / 'np-benchmarks', stdout=log, stderr=log,
                                           env=os.environ | {'BENCH_CAPACITY_MAX_CONNECTIONS': '3'})
                clients = []
                def request(path='/hello'):
                    client = http.client.HTTPConnection('127.0.0.1', port, timeout=1)
                    clients.append(client)
                    client.request('GET', path)
                    response = client.getresponse()
                    return response.status, response.read()
                try:
                    for _ in range(100):
                        try:
                            status, body = request('/__capacity')
                            clients.pop().close()
                            break
                        except OSError:
                            clients.pop().close()
                            if process.poll() is not None:
                                log.seek(0)
                                self.fail(log.read())
                            time.sleep(.1)
                    else:
                        self.fail('server did not start')
                    self.assertEqual(json.loads(body), {'server': name, 'max_connections': 3})
                    time.sleep(.2)
                    for _ in range(3):
                        self.assertEqual(request()[0], 200)
                    try:
                        self.assertNotEqual(request()[0], 200, 'fourth connection exceeded ceiling')
                    except (OSError, http.client.HTTPException):
                        pass
                    for client in clients:
                        client.close()
                    clients.clear()
                    time.sleep(1.2)
                    self.assertEqual(request()[0], 200, 'connection slots were not released')
                    print(name, 'identity, ceiling and recovery passed', flush=True)
                finally:
                    for client in clients:
                        client.close()
                    process.terminate()
                    try:
                        process.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == '__main__':
    unittest.main()
