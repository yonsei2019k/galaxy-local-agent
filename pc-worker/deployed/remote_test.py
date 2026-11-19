"""Owner test requests queued on this PC for the paired phone app (RAM only).

The PC operator enqueues over loopback; the phone, only while its owner has the
time-limited switch on, claims each request once and reports the final answer.
Nothing here is persisted or logged: answers may hold contacts or calendar data.
"""
import re
import threading
import time
import uuid

MAX_TEXT = 2000
MAX_PENDING = 8
MAX_KEPT = 32
CLAIM_TTL_S = 30 * 60
KEEP_S = 6 * 3600
MODEL = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,95}')
STATES = {'completed', 'failed', 'cancelled', 'interrupted', 'paused', 'waiting_approval', 'rejected', 'timeout'}


class RemoteTestError(Exception):
    def __init__(self, code, status=400):
        super().__init__(code)
        self.code = code
        self.status = status


def _text(value, limit):
    if not isinstance(value, str) or len(value) > limit:
        raise RemoteTestError('FIELD')
    return value


class RemoteTestQueue:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.items = {}

    def _prune(self):
        now = self.clock()
        for key, item in list(self.items.items()):
            if now - item['createdAt'] > KEEP_S:
                del self.items[key]
            elif item['state'] == 'claimed' and now - item['claimedAt'] > CLAIM_TTL_S:
                item['state'] = 'timeout'

    def enqueue(self, request):
        if not isinstance(request, dict) or set(request) - {'text', 'model', 'afterRequestId'} or 'text' not in request:
            raise RemoteTestError('SHAPE')
        text = _text(request['text'], MAX_TEXT).strip()
        if not text or text.startswith('/'):
            raise RemoteTestError('TEXT')
        model = request.get('model', 'auto')
        if not isinstance(model, str) or not MODEL.fullmatch(model):
            raise RemoteTestError('MODEL')
        with self.lock:
            self._prune()
            previous = None
            if 'afterRequestId' in request:
                previous = self.items.get(_text(request['afterRequestId'], 36))
                result = previous.get('result') if previous else None
                if (not result or result['state'] != 'completed' or not result.get('canFollowUp')
                        or self.clock() - result['reportedAt'] >= CLAIM_TTL_S
                        or previous['model'] != model or previous.get('followed')):
                    raise RemoteTestError('FOLLOW_UP_UNAVAILABLE', 409)
            if sum(1 for item in self.items.values() if item['state'] == 'pending') >= MAX_PENDING:
                raise RemoteTestError('QUEUE_FULL', 429)
            if len(self.items) >= MAX_KEPT:
                oldest = min((i for i in self.items.values() if i['state'] != 'pending' and i['state'] != 'claimed'),
                             key=lambda i: i['createdAt'], default=None)
                if oldest is None:
                    raise RemoteTestError('QUEUE_FULL', 429)
                del self.items[oldest['requestId']]
            request_id = str(uuid.uuid4())
            self.items[request_id] = {'requestId': request_id, 'text': text, 'model': model, 'state': 'pending',
                                      'createdAt': self.clock(), 'claimedAt': None, 'result': None}
            if previous is not None:
                self.items[request_id]['afterRequestId'] = previous['requestId']
                previous['followed'] = True
            return {'requestId': request_id, 'state': 'pending'}

    def claim(self):
        with self.lock:
            self._prune()
            pending = sorted((i for i in self.items.values() if i['state'] == 'pending'), key=lambda i: i['createdAt'])
            if not pending:
                return {'request': None, 'receiptVersion': 2, 'conversationVersion': 1}
            item = pending[0]
            item['state'] = 'claimed'
            item['claimedAt'] = self.clock()
            fields = ('requestId', 'text', 'model', 'afterRequestId')
            return {'request': {key: item[key] for key in fields if key in item},
                    'receiptVersion': 2, 'conversationVersion': 1}

    def report(self, request_id, body):
        request_id = str(uuid.UUID(request_id))
        if not isinstance(body, dict) or set(body) - {'state', 'answer', 'tools', 'runId', 'errorCode', 'receipt', 'canFollowUp'}:
            raise RemoteTestError('SHAPE')
        receipt = body.get('receipt')
        if 'receipt' in body:
            if (not isinstance(receipt, dict) or set(receipt) != {'versionCode', 'elapsedMs'}
                    or type(receipt['versionCode']) is not int or not 0 < receipt['versionCode'] <= 2**63 - 1
                    or type(receipt['elapsedMs']) is not int or not 0 <= receipt['elapsedMs'] <= 2**63 - 1):
                raise RemoteTestError('RECEIPT')
        state = body.get('state')
        if state not in STATES:
            raise RemoteTestError('STATE')
        if 'canFollowUp' in body and (body['canFollowUp'] is not True or state != 'completed'):
            raise RemoteTestError('FOLLOW_UP')
        answer = _text(body.get('answer', ''), 20000)
        tools = body.get('tools', [])
        if not isinstance(tools, list) or len(tools) > 64:
            raise RemoteTestError('TOOLS')
        clean = []
        for tool in tools:
            if not isinstance(tool, dict) or set(tool) - {'name', 'success', 'summary'}:
                raise RemoteTestError('TOOLS')
            clean.append({'name': _text(tool.get('name', ''), 96), 'success': bool(tool.get('success')),
                          'summary': _text(tool.get('summary', ''), 400)})
        run_id = body.get('runId')
        if run_id is not None:
            run_id = str(uuid.UUID(_text(run_id, 36)))
        error = body.get('errorCode')
        if error is not None:
            error = _text(error, 96)
        with self.lock:
            item = self.items.get(request_id)
            if item is None or item['state'] not in ('claimed', 'timeout'):
                raise RemoteTestError('NOT_CLAIMED', 409)
            item['state'] = 'reported'
            item['result'] = {'state': state, 'answer': answer, 'tools': clean, 'runId': run_id, 'errorCode': error,
                              'reportedAt': self.clock()}
            if receipt is not None:
                item['result']['receipt'] = dict(receipt)
            if body.get('canFollowUp'):
                item['result']['canFollowUp'] = True
            return {'ok': True}

    def status(self, request_id):
        request_id = str(uuid.UUID(request_id))
        with self.lock:
            self._prune()
            item = self.items.get(request_id)
            if item is None:
                raise RemoteTestError('UNKNOWN', 404)
            return {key: item[key] for key in ('requestId', 'state', 'model', 'createdAt', 'claimedAt', 'result')}
