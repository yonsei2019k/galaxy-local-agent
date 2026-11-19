"""Run a synthetic, read-only two-turn canary through the paired test mailbox."""
import argparse
import json
from pathlib import Path
import secrets
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
from local_llm_mcp import call


def run_turn(text, version, previous=None):
    body = {'text': text, 'model': 'paired-pc-cloud-agent-v1'}
    if previous is not None:
        body['afterRequestId'] = previous
    key = call('/v1/remote/requests', body, timeout=5)['requestId']
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        item = call('/v1/remote/requests/' + key, timeout=5)
        result = item.get('result')
        if result is not None:
            receipt = result.get('receipt') or {}
            if receipt.get('versionCode') != version:
                raise RuntimeError('Unexpected installed app version')
            if result.get('state') != 'completed' or result.get('tools'):
                raise RuntimeError('Canary did not complete without tools')
            return key, result
        time.sleep(3)
    raise RuntimeError('Phone result deadline elapsed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version-code', type=int, required=True)
    args = parser.parse_args()
    alias = 'QA-' + secrets.token_hex(6)
    first_id, first = run_turn(
        f'합성 맥락 시험입니다. 이 대화에서만 사용할 별칭은 {alias}입니다. 도구를 쓰지 말고 확인했다고 답해주세요.',
        args.version_code,
    )
    if first.get('canFollowUp') is not True:
        raise RuntimeError('Phone did not authorize a follow-up to this test conversation')
    _, second = run_turn('앞선 요청에서 내가 지정한 별칭만 알려주세요. 도구는 사용하지 마세요.', args.version_code, first_id)
    if alias not in second.get('answer', ''):
        raise RuntimeError('Follow-up did not recover the earlier alias')
    print(json.dumps({'versionCode': args.version_code, 'turns': 2, 'aliasRecalled': True,
                      'elapsedMs': [first['receipt']['elapsedMs'], second['receipt']['elapsedMs']]}))


if __name__ == '__main__':
    main()
