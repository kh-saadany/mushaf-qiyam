#!/usr/bin/env python3
"""
fix_encoder_conv.py:
Transforms ConvInteger nodes in FastConformer ONNX encoder to standard FP32 Conv nodes.
This eliminates ORT_NOT_IMPLEMENTED on ARM64 Android CPU Execution Provider while keeping
the rest of the model (MatMul/Attention/Gemm) INT8-quantized.
"""
import sys
import os
import onnx
import numpy as np
from onnx import numpy_helper, helper

def transform_encoder(input_path, output_path):
    print(f"Loading {input_path}...")
    m = onnx.load(input_path)
    graph = m.graph

    inits = {i.name: i for i in graph.initializer}
    nodes_by_output = {o: n for n in graph.node for o in n.output}
    nodes_by_input = {}
    for n in graph.node:
        for inp in n.input:
            nodes_by_input.setdefault(inp, []).append(n)

    conv_nodes = [n for n in graph.node if n.op_type == "ConvInteger"]
    print(f"Found {len(conv_nodes)} ConvInteger nodes to convert...")

    nodes_to_remove = set()
    inits_to_add = []
    nodes_to_add = []

    for conv in conv_nodes:
        x_q, w_q_name, x_zp_name, w_zp_name = conv.input
        dql = nodes_by_output.get(x_q)
        if not dql or dql.op_type != "DynamicQuantizeLinear":
            print(f"Skipping {conv.name}: unexpected X producer {dql.op_type if dql else 'None'}")
            continue

        x_float = dql.input[0]
        w_q = numpy_helper.to_array(inits[w_q_name])
        w_zp = numpy_helper.to_array(inits[w_zp_name])

        y_q = conv.output[0]
        cast_node = next((c for c in nodes_by_input.get(y_q, []) if c.op_type == "Cast"), None)
        if not cast_node:
            print(f"Skipping {conv.name}: no Cast consumer found")
            continue

        mul_node = next((c for c in nodes_by_input.get(cast_node.output[0], []) if c.op_type == "Mul"), None)
        if not mul_node:
            print(f"Skipping {conv.name}: no Mul consumer found")
            continue

        final_out = mul_node.output[0]
        scale_input = [inp for inp in mul_node.input if inp != cast_node.output[0]][0]
        scale_node = nodes_by_output.get(scale_input)
        if not scale_node or scale_node.op_type != "Mul":
            print(f"Skipping {conv.name}: unexpected scale input")
            continue

        w_scale_name = [inp for inp in scale_node.input if inp != dql.output[1]][0]
        w_scale = numpy_helper.to_array(inits[w_scale_name])

        # Dequantize weights to FP32: (W_q - W_zp) * W_scale
        w_float = (w_q.astype(np.float32) - w_zp.astype(np.float32)) * w_scale
        w_float_name = f"{conv.name}_fp32_weights"
        inits_to_add.append(numpy_helper.from_array(w_float, name=w_float_name))

        # Create standard FP32 Conv node
        new_conv = helper.make_node(
            "Conv",
            inputs=[x_float, w_float_name],
            outputs=[final_out],
            name=f"{conv.name}_fp32"
        )
        for attr in conv.attribute:
            new_conv.attribute.append(attr)

        nodes_to_add.append(new_conv)
        nodes_to_remove.update([conv.name, cast_node.name, mul_node.name, scale_node.name])

    new_nodes = [n for n in graph.node if n.name not in nodes_to_remove] + nodes_to_add
    graph.ClearField("node")
    graph.node.extend(new_nodes)
    graph.initializer.extend(inits_to_add)

    print(f"Saving transformed model to {output_path}...")
    onnx.save(m, output_path)
    print(f"Done! New model size: {os.path.getsize(output_path) / (1024*1024):.2f} MB")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python fix_encoder_conv.py <input_encoder.onnx> <output_encoder.onnx>")
        sys.exit(1)
    transform_encoder(sys.argv[1], sys.argv[2])
