package com.dicksonic.gaios.security.scanner.engine;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * E5 嵌入引擎：把一段文字变成 384 维向量。
 * 三件套分工：tokenizer 分词 → ONNX 模型推理 → mean pooling + L2归一化。
 *
 * 为什么用 ONNX 而不是直接跑 PyTorch 模型：
 * Java 生态没有 PyTorch 原生支持，ONNX 是跨语言推理的标准格式，
 * 也是 MCP-Guard 这类部署场景（政务内网离线机）唯一现实的路径。
 */
public class E5EmbeddingEngine implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(E5EmbeddingEngine.class);

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public E5EmbeddingEngine(Path modelDir) throws Exception {
        this.session = env.createSession(modelDir.resolve("model_quantized_base.onnx").toString());
        this.tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"));
        // multilingual-e5-small 的输出维度
        log.info("E5 引擎加载成功, 输入节点: {}", session.getInputInfo().keySet());
    }

    /**
     * 文本 → 归一化向量。
     * 注意 E5 的硬规则：所有输入必须加 "query: " 或 "passage: " 前缀，
     * 模型是在带前缀的数据上训练的，漏掉前缀向量质量明显变差。
     */
    public float[] embed(String text){
        try{
            Encoding enc = tokenizer.encode("query: " + text);
            long[] ids = enc.getIds();
            long[] mask = enc.getAttentionMask();

            // ONNX 输入按模型实际需要的节点名动态装配：
            // BERT 类模型可能要 token_type_ids，Xenova 导出的版本不一定，缺了就补零
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][]{ids}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][]{mask}));
            //动态探测
            if (session.getInputInfo().containsKey("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][]{new long[ids.length]}));
            }

            try (OrtSession.Result result = session.run(inputs)) {
                // 输出 [1, seqLen, 384]：每个 token 一个向量 模型的第 0 个输出节点
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                OnnxTensor out = (OnnxTensor) result.get(0);
                log.info("输出shape: {}", java.util.Arrays.toString(out.getInfo().getShape()));
                return meanPoolAndNormalize(hidden[0], mask);
            }
        }
        catch (Exception e) {
            // ONNX 推理失败属于运行时故障：调用方无法恢复，只能上报或降级。
            // 所以接口不该抛受检异常强迫每个调用点 try-catch。
            log.error(e.getMessage(),e);
            throw new RuntimeException("E5嵌入失败: " + e.getMessage(), e);
        }
    }

    /** attention_mask 加权平均（屏蔽 padding token）+ L2 归一化（方便余弦相似度=点积） */
    /**
     * 模型输出：h₁, h₂, ..., hₙ ∈ ℝᵈ，n = token 数（含 [CLS]/[SEP]），d = 384；
     * 掩码：mᵢ ∈ {0, 1}，mᵢ = 0 表示第 i 个位置是 padding；
     * 有效 token 数：N = Σᵢ mᵢ。
     */
    private float[] meanPoolAndNormalize(float[][] tokenVecs, long[] mask) {
        int dim = tokenVecs[0].length;
        float[] pooled = new float[dim];
        long tokenCount = 0;
        for (int i = 0; i < tokenVecs.length; i++) {
            if (mask[i] == 0) continue;   // padding 位置跳过
            tokenCount++;
            for (int j = 0; j < dim; j++) pooled[j] += tokenVecs[i][j];
        }
        for (int j = 0; j < dim; j++) pooled[j] /= tokenCount;

        double norm = 0;
        for (float v : pooled) norm += v * v;
        norm = Math.sqrt(norm);
        for (int j = 0; j < dim; j++) pooled[j] /= norm;
        return pooled;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}