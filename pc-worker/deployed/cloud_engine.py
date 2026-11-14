"""Optional Claude engine, used only when the owner selects it. The API key stays on the PC under DPAPI."""
import copy,getpass,json,pathlib,sys
from data_store import _dpapi

ROOT=pathlib.Path(r'D:\ZipHap\Compute')
KEY=ROOT/'anthropic.key.dpapi'
MODEL='claude-opus-5-5'
THINKING_ALLOWANCE=8000

class KeyRequired(Exception):pass

def _client():
    if not KEY.exists():raise KeyRequired()
    vendor=str(ROOT/'vendor')
    if vendor not in sys.path:sys.path.insert(0,vendor)
    import anthropic
    return anthropic.Anthropic(api_key=_dpapi(KEY.read_bytes(),False).decode())

def _strict(schema):
    """Structured outputs take anyOf rather than oneOf and need closed objects."""
    value=copy.deepcopy(schema)
    def walk(node):
        if isinstance(node,dict):
            if 'oneOf' in node:node['anyOf']=node.pop('oneOf')
            if node.get('type')=='object':node.setdefault('additionalProperties',False)
            for child in node.values():walk(child)
        elif isinstance(node,list):
            for child in node:walk(child)
    walk(value);return value

def _sse(value):return ('data: '+json.dumps(value,separators=(',',':'))+'\n\n').encode()

def chat(request,write):
    """Streams the event shape the phone already parses for local generation."""
    if set(request)-{'requestId','modelId','messages','maxTokens','thinking','schema'}:raise ValueError('UNKNOWN_FIELD')
    messages=request['messages'];maximum=request['maxTokens']
    if not isinstance(messages,list) or not 1<=len(messages)<=81 or type(maximum)!=int or not 1<=maximum<=4096:raise ValueError('CLOUD_BOUND')
    system='\n'.join(m['content'] for m in messages if m['role']=='system')
    turns=[{'role':m['role'],'content':m['content']} for m in messages if m['role']!='system']
    client=_client()
    params=dict(model=MODEL,max_tokens=maximum+THINKING_ALLOWANCE,messages=turns,thinking={'type':'adaptive'},
        betas=['server-side-fallback-2026-07-01'],fallbacks='default')
    if system:params['system']=system
    config={'effort':'high'}
    if request.get('schema') is not None:config['format']={'type':'json_schema','schema':_strict(request['schema'])}
    params['output_config']=config
    with client.beta.messages.stream(**params) as stream:
        for text in stream.text_stream:write(_sse({'choices':[{'delta':{'content':text}}]}))
        final=stream.get_final_message()
    if final.stop_reason=='refusal':
        write(_sse({'error':'CLAUDE_REFUSED'}));return
    write(_sse({'choices':[{'delta':{},'finish_reason':'length' if final.stop_reason=='max_tokens' else 'stop'}],
        'usage':{'prompt_tokens':final.usage.input_tokens,'completion_tokens':final.usage.output_tokens}}))
    write(b'data: [DONE]\n\n')

if __name__=='__main__':
    KEY.write_bytes(_dpapi(getpass.getpass('Anthropic API key: ').strip().encode(),True));print('saved')
