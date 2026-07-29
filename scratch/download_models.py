import os
import urllib.request
from huggingface_hub import hf_hub_download

# Create assets dir
assets_dir = r"c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\app\src\main\assets"
os.makedirs(assets_dir, exist_ok=True)

print("Downloading Silero VAD...")
vad_url = "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
urllib.request.urlretrieve(vad_url, os.path.join(assets_dir, "silero_vad.onnx"))
print("VAD downloaded.")

print("Downloading Moonshine-tiny-ar ONNX models...")
repo_id = "onnx-community/moonshine-tiny-ar-ONNX"

files_to_download = [
    "onnx/encoder_model.onnx",
    "onnx/decoder_model_merged.onnx",
    "tokenizer.json"
]

for file in files_to_download:
    print(f"Downloading {file}...")
    local_path = hf_hub_download(repo_id=repo_id, filename=file)
    # copy to assets
    basename = os.path.basename(file)
    with open(local_path, "rb") as f_in:
        with open(os.path.join(assets_dir, basename), "wb") as f_out:
            f_out.write(f_in.read())
    print(f"{basename} copied to assets.")

print("All models downloaded successfully.")
