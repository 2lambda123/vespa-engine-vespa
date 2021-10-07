# Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
import onnx
from onnx import helper, TensorProto

QUERY_TENSOR = helper.make_tensor_value_info('query_tensor', TensorProto.INT32, [1, 4])
ATTRIBUTE_TENSOR = helper.make_tensor_value_info('attribute_tensor', TensorProto.INT32, [4, 1])
BIAS_TENSOR = helper.make_tensor_value_info('bias_tensor', TensorProto.INT32, [1, 1])
OUTPUT = helper.make_tensor_value_info('output', TensorProto.INT32, [1, 1])

nodes = [
    helper.make_node(
        'MatMul',
        ['query_tensor', 'attribute_tensor'],
        ['matmul'],
    ),
    helper.make_node(
        'Add',
        ['matmul', 'bias_tensor'],
        ['output'],
    ),
]
graph_def = helper.make_graph(
    nodes,
    'int_types_scoring',
    [
        QUERY_TENSOR,
        ATTRIBUTE_TENSOR,
        BIAS_TENSOR,
    ],
    [OUTPUT],
)
model_def = helper.make_model(graph_def, producer_name='int_types.py', opset_imports=[onnx.OperatorSetIdProto(version=12)])
onnx.save(model_def, 'int_types.onnx')
