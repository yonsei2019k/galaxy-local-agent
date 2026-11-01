"""Patch only a verified upstream source tree. Models, launch configuration and credentials are untouched."""
import argparse, hashlib, json, pathlib, shutil

COMMIT='37bc88b5e97a213821949732ea66c58ae8ffc784'
def prepare(source, ggml):
    source=pathlib.Path(source);ggml=pathlib.Path(ggml)
    target=source/'src/runtime/denoiser.hpp'
    original=target.read_text(encoding='utf-8')
    assert hashlib.sha256(original.encode()).hexdigest()=='6c35296557d737a24db32d973878631955b672bf0c0bcfa61b28c5c0a2afb0fe', 'Unverified upstream denoiser'
    begin=original.index('static sd::Tensor<float> sample_euler(')
    end=original.index('static sd::Tensor<float> sample_heun(',begin)
    section=original[begin:end]
    assert section.count('for (int i = 0; i < steps; i++)')==1
    section=section.replace('for (int i = 0; i < steps; i++)',
        'ziphap::EulerCheckpoint checkpoint(sigmas);\n    int restored = checkpoint.restore(x);\n    for (int i = restored; i < steps; i++)')
    section=section.replace('if (sigma == sigmas[i + 1]) {\n            continue;',
        'if (sigma == sigmas[i + 1]) {\n            checkpoint.save(x, i + 1);\n            continue;')
    section=section.replace('x += d * (sigmas[i + 1] - sigma);',
        'x += d * (sigmas[i + 1] - sigma);\n        checkpoint.save(x, i + 1);')
    patched=original[:begin]+section+original[end:]
    patched=patched.replace('#include <algorithm>','#include "ziphap_checkpoint.hpp"\n#include <algorithm>',1)
    header=pathlib.Path(__file__).with_name('ziphap_checkpoint.hpp')
    shutil.copyfile(header,source/'src/runtime/ziphap_checkpoint.hpp')
    target.write_text(patched,encoding='utf-8')
    assert not any((source/'ggml').iterdir()), 'Refusing to replace an existing GGML tree'
    shutil.copytree(ggml,source/'ggml',dirs_exist_ok=True)
    return {'commit':COMMIT,'originalDenoiserSha256':hashlib.sha256(original.encode()).hexdigest(),
        'patchedDenoiserSha256':hashlib.sha256(patched.encode()).hexdigest(),
        'checkpointHeaderSha256':hashlib.sha256(header.read_bytes()).hexdigest()}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('source');parser.add_argument('ggml');parser.add_argument('receipt')
    args=parser.parse_args();pathlib.Path(args.receipt).write_text(json.dumps(prepare(args.source,args.ggml),indent=2))
