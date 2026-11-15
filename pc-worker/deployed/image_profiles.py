"""Host-defined model choices; requests cannot supply paths, hashes or native flags."""

from pathlib import Path



DEFAULT_ID='Qwen-Image-2512-20B-Q4_K_M'

SDXL_ID='SDXL-1.0-Q4_K'

ROOT=Path('D:/ZipHap/Compute/Models')

QROOT=ROOT/'qwen-image-2512'

PROFILES={

 DEFAULT_ID:{

  'sha256':'134aa66a4cb27b46e7528486a37d87736a03e48cea6ba88e5de2068543ec6bcc',

  'files':[

   (QROOT/'qwen-image-2512-Q4_K_M.gguf','134aa66a4cb27b46e7528486a37d87736a03e48cea6ba88e5de2068543ec6bcc'),

   (QROOT/'Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf','0f00a930ba3108b6861ddadf74d8ebbd82e257c63eba728e62c3e8970f5eed94'),

   (QROOT/'qwen_image_vae.safetensors','a70580f0213e67967ee9c95f05bb400e8fb08307e017a924bf3441223e023d1f')],

  'arguments':['--diffusion-model',str(QROOT/'qwen-image-2512-Q4_K_M.gguf'),

               '--llm',str(QROOT/'Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf'),

               '--vae',str(QROOT/'qwen_image_vae.safetensors'),

               '--backend','clip=cpu,vae=cpu,diffusion=cuda0',

               '--params-backend','clip=disk,vae=cpu,diffusion=disk',

               '--max-vram','2.5','--auto-fit','on','--disable-prefetch','--flow-shift','3'],

  'cfg':2.5,

 },

 SDXL_ID:{

  'sha256':'51ab2be4ec58905b4cbfe5c0d2f3d0cdc5b0192e8e85dbe9a00481858c8389b0',

  'files':[(ROOT/'sdxl-base-q4k-51ab2be4ec58.gguf','51ab2be4ec58905b4cbfe5c0d2f3d0cdc5b0192e8e85dbe9a00481858c8389b0')],

  'arguments':['--model',str(ROOT/'sdxl-base-q4k-51ab2be4ec58.gguf'),

               '--backend','clip=cpu,vae=cpu,diffusion=cuda0',

               '--params-backend','clip=cpu,vae=cpu,diffusion=disk',

               '--max-vram','2.5','--auto-fit','on','--disable-prefetch'],

  'cfg':7.0,

 },

}



def profile(model_id):

 if type(model_id) is not str or model_id not in PROFILES:raise ValueError('UNKNOWN_IMAGE_MODEL')

 return PROFILES[model_id]



def public_models():return {name:item['sha256'] for name,item in PROFILES.items()}



# Edit-2511 is explicit effective identity for reference generation; never report it as2512.

EDIT_ID='Qwen-Image-Edit-2511-20B-Q4_K_M'

EROOT=ROOT/'qwen-image-edit-2511'

PROFILES[EDIT_ID]={

 'sha256':'07951f61941a81e236cbd4a9b6f250d49626b3ba518f260135bf2e01a87cf1c8',

 'files':[(EROOT/'qwen-image-edit-2511-Q4_K_M.gguf','07951f61941a81e236cbd4a9b6f250d49626b3ba518f260135bf2e01a87cf1c8'),

          *PROFILES[DEFAULT_ID]['files'][1:],

          (EROOT/'Qwen2.5-VL-7B-Instruct.mmproj-f16.gguf','2f9b49529bf463c165223e21f10320655a74da61bb64bf7b9fa8b3892cc46926')],

 'arguments':['--diffusion-model',str(EROOT/'qwen-image-edit-2511-Q4_K_M.gguf'),

               *PROFILES[DEFAULT_ID]['arguments'][2:],

               '--llm_vision',str(EROOT/'Qwen2.5-VL-7B-Instruct.mmproj-f16.gguf'),

               '--model-args','qwen_image_zero_cond_t=true'],

 'cfg':2.5,'requires_reference':True,'supports_reference':True,

}

def reference_models():return [name for name,item in PROFILES.items() if item.get('supports_reference')]


# Same pinned weights and resolution; use the official Vulkan backend on this4GiB GPU.
for _id in (DEFAULT_ID,EDIT_ID):
 PROFILES[_id]['runtime_dir']='runtime-vulkan-37bc88b'
 PROFILES[_id]['arguments']=[arg.replace('diffusion=cuda0','diffusion=Vulkan0') for arg in PROFILES[_id]['arguments']]

# Explicit distilled option: never substitute for an existing Qwen recovery request.
FAST_ID='FLUX.2-klein-4B-Q4_0'
PROFILES[FAST_ID]={
 'sha256':'d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4',
 'files':[(ROOT/'flux-2-klein-4b-Q4_0.gguf','d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4'),
          (ROOT/'qwen3-4b-q5.gguf','37edbd37f5b7a4d4e7a766400c57def1f790e809de03513b86bf11bb06979695'),
          (ROOT/'flux2-vae.safetensors','ca70d2202afe6415bdbcb8793ba8cd99fd159cfe6192381504d6c4d3036e0f04')],
 'arguments':['--diffusion-model',str(ROOT/'flux-2-klein-4b-Q4_0.gguf'),
              '--llm',str(ROOT/'qwen3-4b-q5.gguf'),'--vae',str(ROOT/'flux2-vae.safetensors'),
              '--backend','clip=cpu,vae=Vulkan0,diffusion=Vulkan0',
              '--params-backend','clip=cpu,vae=cpu,diffusion=disk',
              '--max-vram','2.5','--auto-fit','on','--disable-prefetch'],
 'cfg':1.0,'supports_reference':True,'min_steps':4,'default_steps':4,
 'negative_prompt':False,'cpu_threads':4,'runtime_dir':'runtime-checkpoint-37bc88b-v1',
}
