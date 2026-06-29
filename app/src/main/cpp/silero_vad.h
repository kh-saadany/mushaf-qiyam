#pragma once

#include <onnxruntime_cxx_api.h>
#include <vector>
#include <string>
#include <memory>

class SileroVAD {
public:
    SileroVAD(Ort::Env& env, const std::string& model_path);
    ~SileroVAD();

    // Returns probability of speech [0.0, 1.0]
    float process(const std::vector<float>& audio_frame);
    void reset_states();

private:
    std::unique_ptr<Ort::Session> session_;
    Ort::MemoryInfo memory_info_;

    // States
    std::vector<float> h_;
    std::vector<float> c_;
    std::vector<int64_t> sr_;

    // I/O Node Names (using fixed arrays since ORT API takes const char* const*)
    const char* input_node_names_[4] = {"input", "sr", "h", "c"};
    const char* output_node_names_[3] = {"output", "hn", "cn"};
};
