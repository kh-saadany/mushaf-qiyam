#include "silero_vad.h"
#include <stdexcept>
#include <cstring>

SileroVAD::SileroVAD(Ort::Env& env, const std::string& model_path)
    : memory_info_(Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault)) {
    
    Ort::SessionOptions session_options;
    session_options.SetIntraOpNumThreads(1);
    session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    // On Android (Linux), model_path is const char*
    session_ = std::make_unique<Ort::Session>(env, model_path.c_str(), session_options);

    reset_states();
}

SileroVAD::~SileroVAD() = default;

void SileroVAD::reset_states() {
    h_.assign(2 * 1 * 64, 0.0f);
    c_.assign(2 * 1 * 64, 0.0f);
    sr_ = {16000};
}

float SileroVAD::process(const std::vector<float>& audio_frame) {
    if (audio_frame.empty()) return 0.0f;

    // Create input tensors
    std::vector<int64_t> input_shape = {1, static_cast<int64_t>(audio_frame.size())};
    std::vector<int64_t> sr_shape = {1};
    std::vector<int64_t> hc_shape = {2, 1, 64};

    Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
        memory_info_, const_cast<float*>(audio_frame.data()), audio_frame.size(),
        input_shape.data(), input_shape.size());

    Ort::Value sr_tensor = Ort::Value::CreateTensor<int64_t>(
        memory_info_, sr_.data(), sr_.size(),
        sr_shape.data(), sr_shape.size());

    Ort::Value h_tensor = Ort::Value::CreateTensor<float>(
        memory_info_, h_.data(), h_.size(),
        hc_shape.data(), hc_shape.size());

    Ort::Value c_tensor = Ort::Value::CreateTensor<float>(
        memory_info_, c_.data(), c_.size(),
        hc_shape.data(), hc_shape.size());

    std::vector<Ort::Value> inputs;
    inputs.push_back(std::move(input_tensor));
    inputs.push_back(std::move(sr_tensor));
    inputs.push_back(std::move(h_tensor));
    inputs.push_back(std::move(c_tensor));

    // Run inference
    auto output_tensors = session_->Run(
        Ort::RunOptions{nullptr},
        input_node_names_,
        inputs.data(),
        inputs.size(),
        output_node_names_,
        3
    );

    // Extract speech probability
    float* output_data = output_tensors[0].GetTensorMutableData<float>();
    float speech_prob = output_data[0];

    // Update states (hn -> h, cn -> c)
    float* hn = output_tensors[1].GetTensorMutableData<float>();
    float* cn = output_tensors[2].GetTensorMutableData<float>();
    std::memcpy(h_.data(), hn, h_.size() * sizeof(float));
    std::memcpy(c_.data(), cn, c_.size() * sizeof(float));

    return speech_prob;
}
